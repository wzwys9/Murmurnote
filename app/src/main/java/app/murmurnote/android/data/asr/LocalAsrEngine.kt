package app.murmurnote.android.data.asr

import android.content.Context
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 ASR 引擎：sherpa-onnx + SenseVoice（自带 ITN 标点）。
 *
 * 与 sherpa-onnx 类的耦合走反射，目的是让 sherpa-onnx 的 AAR 不在 app/libs/ 时，整个 app 仍能编译运行；
 * 只有"用户实际选了本地引擎并触发转写"那一刻才会感知到反射失败，由 UI 引导其放置 AAR。
 *
 * 并发：内部维护一个 SherpaBridge 池，每个 bridge 持有一个独立的 OfflineRecognizer（~200MB）。
 * 默认池大小 1，用户可在设置页调到 2 或 3。池在第一次 transcribe 或 setConcurrency 时懒初始化。
 */
@Singleton
class LocalAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
    private val logger: Logger
) : AsrEngine {

    override val engineType: AsrEngineType = AsrEngineType.LOCAL_FIRE_RED_ASR

    private val bridges = mutableListOf<SherpaBridge>()
    private var poolSem = Semaphore(1)
    private val roundRobin = AtomicInteger(0)

    override suspend fun isReady(): Boolean = modelReady() && nativeLibReady()

    fun modelReady(): Boolean = modelManager.isModelReady()

    fun nativeLibReady(): Boolean = nativeLibAvailable()

    /**
     * 调整池大小。只在需要扩池时才创建新 bridge（不会缩池，release() 统一清）。
     * 调用时机：AudioPipeline.transcribeAll 在并发跑之前，传入用户设置的并发度。
     */
    fun setConcurrency(n: Int) {
        val target = n.coerceIn(1, 3)
        poolSem = Semaphore(target)
    }

    override suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit
    ): Result<AsrResult> = runCatching {
        if (!modelManager.isModelReady()) {
            throw LocalAsrError.ModelMissing("FireRedASR v2 模型未下载或文件不完整")
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
        bridges.forEach { runCatching { it.release() } }
        bridges.clear()
    }

    /**
     * 从池里取一个 bridge，池不够大就扩。调用者必须持有 poolSem permit 才允许进入。
     * 非同步方法：poolSem 已保证同时只有 concurrency 个协程在执行体内部。
     */
    private fun acquireBridge(): SherpaBridge {
        val idx = roundRobin.getAndIncrement() % maxOf(bridges.size, 1)
        if (idx < bridges.size) return bridges[idx]
        // 池还没初始化或需要扩：在调用者持有的 permit 保护下创建
        val modelDir = modelManager.modelDir()
        val created = try {
            SherpaBridge.create(modelDir, logger)
        } catch (e: ClassNotFoundException) {
            throw LocalAsrError.NativeLibMissing(e)
        } catch (e: NoClassDefFoundError) {
            throw LocalAsrError.NativeLibMissing(RuntimeException(e))
        } catch (e: Throwable) {
            throw LocalAsrError.DecodeFailed("初始化 sherpa-onnx 失败：${e.message}", e)
        }
        bridges.add(created)
        return created
    }

    private fun nativeLibAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        true
    }.getOrDefault(false)
}
