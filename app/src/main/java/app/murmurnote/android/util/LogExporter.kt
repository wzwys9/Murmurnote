package app.murmurnote.android.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.murmurnote.android.BuildConfig
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.data.repository.ApiLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把日志打成 zip 导出到系统下载目录。
 * Zip 内容：
 *   - runtime*.log        当前与轮转文本日志（HTTP / 录音 / Pipeline / 设置等）
 *   - api_logs.txt        最近 100 条 HTTP 请求/响应（默认脱敏并截断）
 *   - meta.txt            APP 版本、构建时间、设备信息
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
    private val apiLogRepository: ApiLogRepository,
    private val asrModelManager: AsrModelManager
) {
    suspend fun exportToDownloads(): Result<String> = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "murmurnote_log_$ts.zip"

        val zipBytes = withContext(Dispatchers.IO) { buildZip() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在 Downloads 创建文件")
            resolver.openOutputStream(uri)?.use { it.write(zipBytes) }
                ?: error("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$displayName"
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloads.mkdirs()
            val dest = File(downloads, displayName)
            dest.outputStream().use { it.write(zipBytes) }
            dest.absolutePath
        }
    }

    /**
     * 把日志包写到 cacheDir/log_share/，再用 FileProvider 包装成 content URI 通过
     * Intent.ACTION_SEND 拉起分享面板。
     * activityContext 应当从 Composable 的 LocalContext.current 传入：FileProvider 授权与
     * 部分 OEM（小米/华为）的 chooser 校验都需要 Activity 上下文，从 ViewModel 注入的
     * ApplicationContext 在某些机型上会被静默拒绝。
     */
    suspend fun exportAndShare(activityContext: Context): Result<Unit> = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "murmurnote_log_$ts.zip"

        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "log_share").apply { mkdirs() }
            // 清掉之前的旧分享文件，避免缓存目录无限增长（FileProvider 的 cache-path 不会自动清理）
            dir.listFiles()?.forEach { if (it.name.startsWith("murmurnote_log_")) it.delete() }
            val dest = File(dir, fileName)
            dest.writeBytes(buildZip())
            dest
        }

        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(activityContext, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Murmurnote 日志 $ts")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "分享日志包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activityContext.startActivity(chooser)
    }

    private suspend fun buildZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 1) 当前与轮转 runtime 日志
            val files = logger.logFiles().filter { it.exists() && it.length() > 0 }
            if (files.isEmpty()) {
                writeStringEntry(zip, "runtime.log", "(空：APP 还未产生任何运行日志)")
            } else {
                files.forEach { writeFileEntry(zip, it, it.name) }
            }

            // 2) 最近 100 条 HTTP 调用（DB 中已按默认策略脱敏和截断）
            writeStringEntry(zip, "api_logs.txt", buildApiLogText())

            // 3) 元信息
            writeStringEntry(zip, "meta.txt", buildMetaText())
        }
        return out.toByteArray()
    }

    private fun writeFileEntry(zip: ZipOutputStream, src: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(src).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun writeStringEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private suspend fun buildApiLogText(): String {
        val logs = runCatching { apiLogRepository.getRecent(100) }.getOrNull() ?: return "(无法读取 api_logs)"
        if (logs.isEmpty()) return "(无 API 调用记录)"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("=== Recent API calls (newest first) ===")
        sb.appendLine()
        logs.forEach { log ->
            sb.appendLine("[${fmt.format(Date(log.timestamp))}] ${log.apiName}  ${log.method}  HTTP ${log.responseCode}  ${log.durationMs}ms")
            sb.appendLine("  URL: ${log.url}")
            log.requestBody?.let { sb.appendLine("  Request: ${LogSanitizer.body(it, 2000)}") }
            log.responseBody?.let { sb.appendLine("  Response: ${LogSanitizer.body(it, 4000)}") }
            log.errorMessage?.let { sb.appendLine("  Error: ${LogSanitizer.message(it)}") }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun buildMetaText(): String {
        val sb = StringBuilder()
        sb.appendLine("Murmurnote diagnostic bundle")
        sb.appendLine("--------------------------------")
        sb.appendLine("Exported at:  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        sb.appendLine("App version:  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Build type:   ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("Application:  ${BuildConfig.APPLICATION_ID}")
        sb.appendLine("Device:       ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android:      ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI:          ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("ASR bundled:  ${asrModelManager.hasBundledAssets()}")
        sb.appendLine("ASR ready:    ${asrModelManager.isModelReady()}")
        sb.appendLine("ASR files:    ${directorySize(asrModelManager.modelDir())}")
        return sb.toString()
    }

    private fun directorySize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
}
