package app.murmurnote.android.data.asr

import android.content.Context
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 ASR 模型的下载 / 校验 / 解压 / 状态查询 / 删除。
 *
 * 仓库布局（context.filesDir）：
 *   asr_models/
 *     sense_voice_zh_en_ja_ko_yue/
 *       model.int8.onnx
 *       tokens.txt
 *     sense_voice.downloading   ← 临时文件，下载中或被中断
 *
 * 下载：OkHttp + Range 断点续传。请求 200 直接覆盖；206 追加。下载到 *.downloading，
 * 校验 + 解压完毕才 delete 它，确保不会被半成品当成完整文件。
 *
 * 状态：MutableStateFlow，UI collectAsState 渲染。下载/解压共用一个 progress 0..1，分阶段：
 *   0..0.85   下载
 *   0.85..1.0 解压
 */
@Singleton
class AsrModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val logger: Logger,
    @ApplicationScope private val scope: CoroutineScope
) {

    sealed class ModelStatus {
        data object NotDownloaded : ModelStatus()
        data class Downloading(val progress: Float, val bytesPerSec: Long, val etaSec: Long) : ModelStatus()
        data class Extracting(val progress: Float) : ModelStatus()
        data class Ready(val sizeBytes: Long) : ModelStatus()
        data class Corrupted(val reason: String) : ModelStatus()
        data class Failed(val message: String) : ModelStatus()
    }

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: Flow<ModelStatus> = _status.asStateFlow()

    @Volatile private var cancelRequested = false
    @Volatile private var selectedModelId: String = AsrModelUrls.DEFAULT_MODEL_ID

    init {
        scope.launch {
            appPreferences.asrLocalModelId.collect { id ->
                selectedModelId = AsrModelUrls.modelById(id).id
                refreshStatus()
            }
        }
    }

    fun selectedModel(): LocalAsrModelSpec = AsrModelUrls.modelById(selectedModelId)

    fun availableModels(): List<LocalAsrModelSpec> = AsrModelUrls.MODELS

    suspend fun selectModel(id: String) {
        val normalized = AsrModelUrls.modelById(id).id
        selectedModelId = normalized
        appPreferences.setAsrLocalModelId(normalized)
        refreshStatus()
    }

    fun modelDir(): File = modelDir(selectedModel())

    private fun modelDir(model: LocalAsrModelSpec): File = File(context.filesDir, "asr_models/${model.id}").apply { mkdirs() }
    private fun rootDir(): File = File(context.filesDir, "asr_models").apply { mkdirs() }
    private fun tarballFile(model: LocalAsrModelSpec = selectedModel()): File = File(rootDir(), model.partialFileName)

    /**
     * 同步算一次"当前模型在不在"。AsrEngineProvider / LocalAsrEngine 调用，不写状态流。
     *
     * 按当前选中的模型规格判断文件是否就绪。
     */
    fun isModelReady(): Boolean = isModelReady(selectedModel())

    private fun isModelReady(model: LocalAsrModelSpec): Boolean {
        val dir = modelDir(model)
        if (!dir.exists() || !dir.isDirectory) return false
        val required = if (model.id == AsrModelUrls.QWEN3_ASR_ID) {
            listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer")
        } else {
            listOf("model.int8.onnx", "tokens.txt")
        }
        val missing = required.filter { rel ->
            val f = File(dir, rel)
            !f.exists() || (f.isFile && f.length() <= 0L) || (f.isDirectory && f.list().isNullOrEmpty())
        }
        if (missing.isNotEmpty()) {
            logger.d("ModelMgr", "isModelReady=false: ${model.id} 文件缺失或为空：$missing")
            return false
        }
        val onnxTotal = onnxTotalBytes(dir)
        if (onnxTotal < model.minOnnxTotalBytes) {
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .joinToString(", ") { "${it.relativeTo(dir).path}=${it.length()}" }
                .ifBlank { "<空目录>" }
            logger.d("ModelMgr", "isModelReady=false: onnx 总大小 $onnxTotal < ${model.minOnnxTotalBytes}; 目录内容=$files")
            return false
        }
        return true
    }

    /** 重新计算并广播状态。UI 进入设置页或下载完成后调一次。 */
    suspend fun refreshStatus() {
        val s = computeStatus()
        _status.value = s
    }

    private fun computeStatus(): ModelStatus {
        if (isModelReady()) {
            // 模型目录里所有运行文件总和，UI 显示"占用空间"用
            val totalBytes = directorySize(modelDir())
            return ModelStatus.Ready(totalBytes)
        }
        // 已部分下载到 tarball 但还没解压成功 → 视为 NotDownloaded（用户继续点下载会断点续）
        return ModelStatus.NotDownloaded
    }

    /** 用户点下载触发。挂起完成后再观察 status 拿最终结果。 */
    suspend fun downloadAndInstall(): Result<Unit> = withContext(Dispatchers.IO) {
        cancelRequested = false
        runCatching {
            val model = selectedModel()
            val mirrorIndex = appPreferences.asrDownloadMirrorIndex.first()
                .coerceIn(0, AsrModelUrls.MIRROR_PREFIXES.lastIndex)
            val tarball = downloadWithFallback(model, mirrorIndex)
            if (cancelRequested) throw CancellationException("下载已取消")

            val expected = model.tarballSha256
            if (expected.isBlank()) {
                val msg = "模型 ${model.id} 未配置 SHA256，拒绝安装未校验的下载文件"
                _status.value = ModelStatus.Corrupted(msg)
                error(msg)
            }
            _status.value = ModelStatus.Extracting(0.86f)
            val actual = sha256(tarball)
            if (!actual.equals(expected, ignoreCase = true)) {
                tarball.delete()
                val msg = "SHA256 校验失败：期望=$expected 实际=$actual"
                _status.value = ModelStatus.Corrupted(msg)
                error(msg)
            }

            // 解压
            extractTarBz2(model, tarball)

            if (!isModelReady(model)) {
                val msg = "解压完成后模型文件大小异常"
                _status.value = ModelStatus.Corrupted(msg)
                error(msg)
            }
            tarball.delete()
            refreshStatus()
        }.onFailure { e ->
            logger.e("ModelMgr", "downloadAndInstall failed: ${e.message}", e)
            if (e is CancellationException) {
                _status.value = ModelStatus.NotDownloaded
            } else {
                _status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        cancelRequested = true
        logger.i("ModelMgr", "cancel requested")
    }

    /** 用户点删除：只清掉当前选中的模型。释放资源，重置状态。 */
    suspend fun delete() = withContext(Dispatchers.IO) {
        val model = selectedModel()
        modelDir(model).deleteRecursively()
        tarballFile(model).delete()
        File(rootDir(), "_staging").deleteRecursively()
        // 删除后清掉"已从 assets 安装"标志，下次启动会重新从 assets 拷一遍
        appPreferences.setAsrBundledInstalled(false)
        _status.value = ModelStatus.NotDownloaded
        logger.i("ModelMgr", "model files deleted: ${model.id}")
    }

    /**
     * 如果 assets 里预置了模型且 filesDir 里还没有，从 assets 拷过来。
     * 调用方：MurmurnoteApplication.onCreate（启动后台任务）+ SettingsViewModel.init（进设置页时主动拷）。
     *
     * sherpa-onnx 加载模型只接受文件路径，不直接读 assets，所以必须有这一步一次性物理化。
     * 拷贝完写 prefs 标志，下次启动直接跳过；用户点"删除模型"时把标志清掉，下次再装回来。
     */
    suspend fun installBundledModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady()) return@withContext true
        val model = selectedModel()
        val am = context.assets
        val assetRoot = model.assetRoot
        val assetEntries = runCatching { listAssetFiles(assetRoot) }
            .getOrElse { emptyList() }
        if (assetEntries.isEmpty()) {
            logger.d("ModelMgr", "no bundled model in assets/$assetRoot")
            return@withContext false
        }
        logger.i("ModelMgr", "installing bundled model from assets, ${assetEntries.size} files")
        val outDir = modelDir(model).apply {
            deleteRecursively()
            mkdirs()
        }
        // 估总大小做进度条；assets 拷不出 totalBytes，逐文件拷 + 用文件序号当 rough 进度。
        var done = 0
        val total = assetEntries.size
        for (relativePath in assetEntries) {
            if (cancelRequested) throw CancellationException("安装已取消")
            val target = File(outDir, relativePath)
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            am.open("$assetRoot/$relativePath").use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        // 节流上报：每 2MB 一次
                        if ((written and ((1L shl 21) - 1)) == 0L) {
                            val rough = (done + 0.5f) / total
                            _status.value = ModelStatus.Extracting(0.85f + rough * 0.15f)
                            yield()
                        }
                    }
                }
            }
            done++
            val rough = done.toFloat() / total
            _status.value = ModelStatus.Extracting(0.85f + rough * 0.15f)
            logger.i("ModelMgr", "asset → ${target.relativeTo(outDir).path} size=${target.length()}")
        }
        deleteNonRuntimeModelFiles(outDir)
        if (!isModelReady()) {
            val msg = "assets 拷贝完成但文件大小异常"
            _status.value = ModelStatus.Corrupted(msg)
            logger.e("ModelMgr", msg, null)
            return@withContext false
        }
        appPreferences.setAsrBundledInstalled(true)
        refreshStatus()
        logger.i("ModelMgr", "bundled model install done")
        true
    }

    /** 是否在 assets 里塞了内置模型；UI 用来决定要不要显示"下载模型"按钮。 */
    fun hasBundledAssets(): Boolean = runCatching {
        context.assets.list(selectedModel().assetRoot)?.isNotEmpty() == true
    }.getOrDefault(false)

    // -------------------- 下载实现 --------------------

    /**
     * 顺序尝试镜像（从 mirrorIndex 起），每个镜像都用 Range 续传到同一个 tarball 文件。
     * 一个镜像在"持续 10s 速度低于 50KB/s"时主动放弃切下一个。
     */
    private suspend fun downloadWithFallback(model: LocalAsrModelSpec, startIndex: Int): File {
        val tarball = tarballFile(model)
        if (tarball.exists()) {
            when {
                isCompleteTarball(model, tarball) -> {
                    logger.i("ModelMgr", "reuse complete tarball for ${model.id}: size=${tarball.length()}")
                    return tarball
                }
                tarball.length() > model.tarballBytes -> {
                    logger.w("ModelMgr", "partial tarball is larger than expected, restart download: size=${tarball.length()} expected=${model.tarballBytes}")
                    tarball.delete()
                }
            }
        }
        val tried = mutableListOf<String>()
        val ordered = (startIndex until AsrModelUrls.MIRROR_PREFIXES.size).map { it } +
            (0 until startIndex).map { it }
        for (i in ordered) {
            if (cancelRequested) throw CancellationException("下载已取消")
            val prefix = AsrModelUrls.MIRROR_PREFIXES[i]
            val url = prefix + model.tarballUrl
            tried += url
            try {
                downloadOne(model, url, tarball)
                return tarball
            } catch (e: SlowDownloadAbort) {
                logger.w("ModelMgr", "镜像 #$i 速度过慢，切下一个：$url")
                // 不删 tarball，下个镜像继续 Range 续传
            } catch (e: IOException) {
                logger.w("ModelMgr", "镜像 #$i IO 失败：${e.message}")
            } catch (e: CancellationException) {
                throw e
            }
        }
        error("所有镜像下载均失败：$tried")
    }

    /**
     * 下载单一 URL 到 tarball；如果文件已部分存在，发 Range: bytes=N- 续传。
     * 在每次写盘时检查 cancel + 速度采样，触发慢速 abort 抛 SlowDownloadAbort。
     */
    private suspend fun downloadOne(model: LocalAsrModelSpec, url: String, dest: File) {
        var existing = if (dest.exists()) dest.length() else 0L
        if (existing > model.tarballBytes) {
            logger.w("ModelMgr", "local tarball exceeds expected size; deleting before retry: $existing > ${model.tarballBytes}")
            dest.delete()
            existing = 0L
        }
        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        logger.i("ModelMgr", "download begin url=$url offset=$existing")

        okHttpClient.newCall(request).execute().use { resp: Response ->
            if (resp.code == 416 && isCompleteTarball(model, dest)) {
                logger.i("ModelMgr", "server returned 416 but local tarball is complete; skip download and reuse ${dest.name}")
                return
            }
            if (resp.code == 416 && existing > 0) {
                dest.delete()
                throw IOException("HTTP 416 Range Not Satisfiable; 已清理本地断点文件，请重试从头下载")
            }
            if (resp.code !in setOf(200, 206)) {
                val body = runCatching { resp.body?.string()?.take(200) }.getOrNull().orEmpty()
                throw IOException("HTTP ${resp.code} $body")
            }
            val body = resp.body ?: throw IOException("空响应体")
            val totalContent = body.contentLength()
            // Content-Length 在 206 是"剩余字节数"，要加上 existing 才是文件总长
            val totalBytes = if (resp.code == 206 && totalContent > 0) existing + totalContent
                else if (totalContent > 0) totalContent
                else model.tarballBytes

            // 200 表示服务器忽略 Range，从头开始 → 截断重写
            val raf = RandomAccessFile(dest, "rw")
            try {
                if (resp.code == 200) raf.setLength(0)
                raf.seek(if (resp.code == 206) existing else 0L)

                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var written = if (resp.code == 206) existing else 0L
                    var lastSampleTime = System.currentTimeMillis()
                    var lastSampleBytes = written
                    var slowSinceMs: Long? = null
                    var emittedAt = 0L

                    while (true) {
                        if (cancelRequested) throw CancellationException("下载已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n

                        val now = System.currentTimeMillis()
                        val sampleElapsed = now - lastSampleTime
                        if (sampleElapsed >= 1000) {
                            val bps = ((written - lastSampleBytes) * 1000L) / sampleElapsed
                            lastSampleTime = now
                            lastSampleBytes = written

                            // 慢速保护：连续 10s 低于 50KB/s 切镜像
                            if (bps < 50 * 1024) {
                                if (slowSinceMs == null) slowSinceMs = now
                                else if (now - (slowSinceMs ?: now) >= 10_000) {
                                    logger.w("ModelMgr", "速度过慢 $bps B/s 持续 ≥10s，触发镜像切换")
                                    throw SlowDownloadAbort
                                }
                            } else {
                                slowSinceMs = null
                            }

                            // 节流 UI 状态：1s 一次
                            if (now - emittedAt >= 1000) {
                                emittedAt = now
                                val progressDownload = if (totalBytes > 0) (written.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                                val progressTotal = (progressDownload * 0.85f).coerceIn(0f, 0.85f)
                                val etaSec = if (bps > 0) ((totalBytes - written) / bps).coerceAtLeast(0L) else 0L
                                _status.value = ModelStatus.Downloading(progressTotal, bps, etaSec)
                            }
                        }
                        // 大文件 IO 协程友好
                        if ((written and 0x7FFFF) == 0L) yield()
                    }
                }
            } finally {
                runCatching { raf.close() }
            }
        }
        logger.i("ModelMgr", "download done url=$url size=${dest.length()}")
    }

    private fun isCompleteTarball(model: LocalAsrModelSpec, file: File): Boolean =
        file.exists() && file.isFile && file.length() == model.tarballBytes

    private object SlowDownloadAbort : RuntimeException() {
        private fun readResolve(): Any = SlowDownloadAbort
    }

    // -------------------- 校验 + 解压 --------------------

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun extractTarBz2(model: LocalAsrModelSpec, tarball: File) {
        val outRoot = rootDir()
        val expectedTopDir = model.tarballTopDir
        // 先解到一个临时位置，避免半成品占据当前模型目录。
        val staging = File(outRoot, "_staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val tarSize = tarball.length().coerceAtLeast(1)
        var processed = 0L

        // 解压阶段按字节进度刷新，避免大文件 entry 期间 UI 长时间卡在 85%。
        // 改成每 ~1MB 或 500ms 节流刷一次，UI 持续动起来。
        var lastEmittedAt = 0L
        var lastEmittedBytes = 0L
        FileInputStream(tarball).use { fin ->
            BZip2CompressorInputStream(fin.buffered()).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (cancelRequested) throw CancellationException("解压已取消")
                        val target = File(staging, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (cancelRequested) throw CancellationException("解压已取消")
                                    val n = tar.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    processed += n

                                    val now = System.currentTimeMillis()
                                    if (processed - lastEmittedBytes >= 1024 * 1024 || now - lastEmittedAt >= 500) {
                                        // tarball 是压缩后大小，processed 是解压后字节，二者比值通常 1.0–1.2，
                                        // coerceIn 兜底防止超过 0.99。
                                        val rough = (processed.toFloat() / tarSize).coerceIn(0f, 0.99f)
                                        _status.value = ModelStatus.Extracting(0.85f + rough * 0.15f)
                                        lastEmittedAt = now
                                        lastEmittedBytes = processed
                                        yield()
                                    }
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }

        // 把 staging/<expectedTopDir>/* rename 到当前模型目录。
        val src = File(staging, expectedTopDir).takeIf { it.exists() }
            ?: staging.listFiles()?.firstOrNull { it.isDirectory }
            ?: error("解压后找不到模型顶层目录")
        val dest = modelDir(model)
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!src.renameTo(dest)) {
            // rename 失败（跨设备等）→ 复制
            src.copyRecursively(dest, overwrite = true)
            src.deleteRecursively()
        }
        deleteNonRuntimeModelFiles(dest)
        staging.deleteRecursively()
        logger.i("ModelMgr", "extract done → ${dest.absolutePath}")
    }

    private fun onnxTotalBytes(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".onnx") }
            .sumOf { it.length() }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    private fun deleteNonRuntimeModelFiles(dir: File) {
        File(dir, "test_wavs").deleteRecursively()
        File(dir, "README.md").delete()
    }

    private fun listAssetFiles(assetRoot: String): List<String> {
        fun walk(assetPath: String, relativePath: String): List<String> {
            val children = context.assets.list(assetPath)?.toList().orEmpty()
            if (children.isEmpty()) return listOf(relativePath)
            return children.flatMap { child ->
                val childAssetPath = "$assetPath/$child"
                val childRelativePath = if (relativePath.isEmpty()) child else "$relativePath/$child"
                walk(childAssetPath, childRelativePath)
            }
        }

        return context.assets.list(assetRoot)
            ?.toList()
            .orEmpty()
            .flatMap { child -> walk("$assetRoot/$child", child) }
    }
}
