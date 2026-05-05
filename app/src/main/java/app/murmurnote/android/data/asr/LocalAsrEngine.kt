package app.murmurnote.android.data.asr

import android.content.Context
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地 ASR 引擎：sherpa-onnx + FireRedASR v2 CTC。
 *
 * 与 sherpa-onnx 类的耦合走反射，目的是让 sherpa-onnx 的 AAR 不在 app/libs/ 时，整个 app 仍能编译运行；
 * 只有"用户实际选了本地引擎并触发转写"那一刻才会感知到反射失败，由 UI 引导其放置 AAR。
 *
 * 输入：单文件，已是 mono 16kHz WAV（与云端共享 AudioConverter + AudioSplitter 的产物）。
 * 输出：每段 decode 一次，对外回单段最终文本；段时长 0 由 Pipeline 自己用 Slice 元数据填。
 */
@Singleton
class LocalAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
    private val logger: Logger
) : AsrEngine {

    override val engineType: AsrEngineType = AsrEngineType.LOCAL_FIRE_RED_ASR

    /** 反射缓存：第一次 initializeIfNeeded 成功后填入；release 时清空。 */
    @Volatile private var bridge: SherpaBridge? = null

    override suspend fun isReady(): Boolean = modelReady() && nativeLibReady()

    /** 模型文件检查（独立暴露：UI / Provider 可以分别看模型与原生库状态）。 */
    fun modelReady(): Boolean = modelManager.isModelReady()

    /** sherpa-onnx Kotlin/JNI 类在 classpath 里 = AAR 已打进 APK。 */
    fun nativeLibReady(): Boolean = nativeLibAvailable()

    override suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit
    ): Result<AsrResult> = runCatching {
        if (!modelManager.isModelReady()) {
            throw LocalAsrError.ModelMissing("FireRedASR v2 模型未下载或文件不完整")
        }
        val br = initializeIfNeeded()
        onProgress(0.1f)
        val samples = WavReader.readMono16kPcm(wav)
        onProgress(0.3f)
        val text = br.decode(samples)
        onProgress(1f)
        AsrResult(text = text, durationMs = (samples.size * 1000L / WavReader.TARGET_SR))
    }.onFailure { logger.e("LocalAsr", "transcribe failed for ${wav.name}: ${it.message}", it) }

    override fun release() {
        bridge?.runCatching { release() }
        bridge = null
    }

    private fun nativeLibAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        true
    }.getOrDefault(false)

    private fun initializeIfNeeded(): SherpaBridge {
        bridge?.let { return it }
        synchronized(this) {
            bridge?.let { return it }
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
            bridge = created
            return created
        }
    }
}
