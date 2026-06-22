package app.murmurnote.android.data.asr

import android.content.Context
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 ASR 引擎：sherpa-onnx + SenseVoiceSmall int8。
 *
 * 与 sherpa-onnx 类的耦合走反射，目的是让 sherpa-onnx 的 AAR 不在 app/libs/ 时，整个 app 仍能编译运行；
 * 只有"用户实际选了本地引擎并触发转写"那一刻才会感知到反射失败，由 UI 引导其放置 AAR。
 *
 * 并发：固定单实例串行解码，避免并发加载多个 OfflineRecognizer 造成移动端内存压力。
 */
@Singleton
class LocalAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
    private val appPreferences: AppPreferences,
    private val logger: Logger
) : AsrEngine {

    override val engineType: AsrEngineType
        get() = if (modelManager.selectedModel().id == AsrModelUrls.QWEN3_ASR_ID) {
            AsrEngineType.LOCAL_QWEN3_ASR
        } else {
            AsrEngineType.LOCAL_SENSE_VOICE
        }

    private val bridges = mutableListOf<SherpaBridge>()
    private val bridgeLock = Any()
    private var poolSem = Semaphore(1)
    private val roundRobin = AtomicInteger(0)
    @Volatile private var maxConcurrency = 1
    @Volatile private var loadedModelId: String? = null

    override suspend fun isReady(): Boolean = modelReady() && nativeLibReady()

    fun modelReady(): Boolean = modelManager.isModelReady()

    fun nativeLibReady(): Boolean = nativeLibAvailable()

    /**
     * 小模型允许 1x-3x 并行识别；大模型强制单路，避免移动端内存压力。
     * 调用时机：AudioPipeline.transcribeAll 在并发跑之前，传入用户设置的并发度。
     */
    fun setConcurrency(n: Int) {
        val allowed = if (modelManager.selectedModel().supportsFastConcurrency) n.coerceIn(1, 3) else 1
        maxConcurrency = allowed
        poolSem = Semaphore(allowed)
        logger.i("LocalAsr", "local concurrency set to ${allowed}x model=${modelManager.selectedModel().id}")
    }

    suspend fun preferredConcurrency(): Int {
        val requested = appPreferences.asrLocalConcurrency.first().coerceIn(1, 3)
        return if (modelManager.selectedModel().supportsFastConcurrency) requested else 1
    }

    override suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit
    ): Result<AsrResult> = runCatching {
        if (!modelManager.isModelReady()) {
            throw LocalAsrError.ModelMissing("${modelManager.selectedModel().displayName} 模型未下载或文件不完整")
        }
        poolSem.withPermit {
            val br = acquireBridge()
            onProgress(0.1f)
            val samples = WavReader.readMono16kPcm(wav)
            onProgress(0.3f)
            val text = br.decode(samples)
            onProgress(1f)
            AsrResult(text = text, durationMs = (samples.size * 1000L / WavReader.TARGET_SR))
        }
    }.onFailure { logger.e("LocalAsr", "transcribe failed for ${wav.name}: ${it.message}", it) }

    override fun release() {
        synchronized(bridgeLock) {
            bridges.forEach { runCatching { it.release() } }
            bridges.clear()
            loadedModelId = null
        }
    }

    /**
     * 从池里取一个 bridge，池不够大就扩。调用者必须持有 poolSem permit 才允许进入。
     * 非同步方法：poolSem 已保证同时只有 concurrency 个协程在执行体内部。
     */
    private fun acquireBridge(): SherpaBridge = synchronized(bridgeLock) {
        val model = modelManager.selectedModel()
        if (loadedModelId != null && loadedModelId != model.id) {
            bridges.forEach { runCatching { it.release() } }
            bridges.clear()
            loadedModelId = null
        }
        val idx = roundRobin.getAndIncrement().floorMod(maxOf(maxConcurrency, 1))
        if (idx < bridges.size) return@synchronized bridges[idx]
        // 池还没初始化或需要扩：在调用者持有的 permit 保护下创建
        val modelDir = modelManager.modelDir()
        val created = try {
            SherpaBridge.create(modelDir, numThreads = maxConcurrency, logger = logger)
        } catch (e: ClassNotFoundException) {
            throw LocalAsrError.NativeLibMissing(e)
        } catch (e: NoClassDefFoundError) {
            throw LocalAsrError.NativeLibMissing(RuntimeException(e))
        } catch (e: Throwable) {
            throw LocalAsrError.DecodeFailed("初始化 sherpa-onnx 失败：${e.message}", e)
        }
        bridges.add(created)
        loadedModelId = model.id
        created
    }

    private fun nativeLibAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        true
    }.getOrDefault(false)
}

private fun Int.floorMod(other: Int): Int = Math.floorMod(this, other)
