package app.murmurnote.android.data.asr

import app.murmurnote.android.util.Logger
import java.io.File
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * 反射桥接 sherpa-onnx Kotlin/JNI API。本地 ASR 引擎专用。
 *
 * 设计目的：sherpa-onnx 的 AAR 没塞到 app/libs/ 时整个 app 仍能编译运行；
 * 只有真正调用本地引擎那一刻才感知到缺失。所有反射细节集中在这一文件里，
 * LocalAsrEngine 看到的是干净的 decode(samples) → String。
 *
 * 反射目标 API（sherpa-onnx Android 1.12.x，包名 com.k2fsa.sherpa.onnx）：
 *   - data class OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder, tokenizer, ...)
 *   - data class OfflineFireRedAsrModelConfig(encoder: String = "", decoder: String = "")
 *   - data class OfflineModelConfig(... qwen3Asr, fireRedAsr, tokens, numThreads, debug, provider, modelType ...)
 *   - data class OfflineRecognizerConfig(... modelConfig, decodingMethod ...)
 *   - class OfflineRecognizer(config)
 *       fun createStream(): OfflineStream
 *       fun decode(stream: OfflineStream)
 *       fun getResult(stream: OfflineStream): OfflineRecognizerResult { val text: String }
 *       fun release()
 *   - class OfflineStream
 *       fun acceptWaveform(samples: FloatArray, sampleRate: Int)
 *       fun release()
 *
 * 用 kotlin-reflect 的 primaryConstructor.callBy(...) 按字段名传值，sherpa-onnx 在数据类里加新字段
 * （只要带默认值）不会让我们崩。一旦有"必填新字段"加进来，构造会抛 IllegalArgumentException：
 * 看 runtime.log 里 [LocalAsr] 的具体异常信息再扩参数。
 */
class SherpaBridge private constructor(
    private val recognizer: Any,
    private val recognizerCls: Class<*>,
    private val streamCls: Class<*>,
    private val mResultText: Method,
    private val logger: Logger
) {

    private val mCreateStream: Method = recognizerCls.getMethod("createStream")
    private val mDecode: Method = recognizerCls.getMethod("decode", streamCls)
    private val mGetResult: Method = recognizerCls.getMethod("getResult", streamCls)
    private val mAcceptWaveform: Method =
        streamCls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
    private val mStreamRelease: Method = streamCls.getMethod("release")
    private val mRecognizerRelease: Method = recognizerCls.getMethod("release")

    /** 喂一段 mono 16k Float PCM，返回识别文本。线程不安全：调用者保证串行。 */
    fun decode(samples: FloatArray): String {
        val stream = mCreateStream.invoke(recognizer)
            ?: error("OfflineRecognizer.createStream() returned null")
        try {
            mAcceptWaveform.invoke(stream, samples, SAMPLE_RATE)
            mDecode.invoke(recognizer, stream)
            val result = mGetResult.invoke(recognizer, stream)
                ?: error("OfflineRecognizer.getResult() returned null")
            return mResultText.invoke(result) as? String ?: ""
        } finally {
            runCatching { mStreamRelease.invoke(stream) }
        }
    }

    fun release() {
        runCatching { mRecognizerRelease.invoke(recognizer) }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val PKG = "com.k2fsa.sherpa.onnx"

        /** 用模型文件大小区分 SingleFile 布局：SenseVoice ~226MB，FireRedASR CTC ~740MB。 */
        private const val SINGLE_FILE_MODEL_SIZE_THRESHOLD = 500L * 1024 * 1024

        /**
         * 在 IO 线程调；构造 OfflineRecognizer（加载 ONNX 模型）。
         *
         * 支持四种模型布局，按文件存在自动探测：
         *   - Qwen3-ASR 0.6B (conv_frontend + encoder + decoder + tokenizer)
         *   - SenseVoice (model.int8.onnx < 500MB)：ITN 开启
         *   - FireRedASR CTC (model.int8.onnx > 500MB)：纯字符，无标点
         *   - FireRedASR AED (encoder.int8.onnx + decoder.int8.onnx)：encoder+decoder
         */
        fun create(modelDir: File, logger: Logger): SherpaBridge {
            val tokens = File(modelDir, "tokens.txt").absolutePath
            val convFrontendFile = File(modelDir, "conv_frontend.onnx")
            val modelFile = File(modelDir, "model.int8.onnx")
            val encoderFile = File(modelDir, "encoder.int8.onnx")
            val decoderFile = File(modelDir, "decoder.int8.onnx")
            val tokenizerDir = File(modelDir, "tokenizer")
            val modelConfig: Any = when {
                convFrontendFile.exists() && encoderFile.exists() && decoderFile.exists() && tokenizerDir.isDirectory -> {
                    logger.i(
                        "LocalAsr",
                        "Qwen3-ASR layout (onnx ~${listOf(convFrontendFile, encoderFile, decoderFile).sumOf { it.length() } / 1024 / 1024}MB)"
                    )
                    val qwen3Peer = constructByName(
                        "$PKG.OfflineQwen3AsrModelConfig",
                        mapOf(
                            "convFrontend" to convFrontendFile.absolutePath,
                            "encoder" to encoderFile.absolutePath,
                            "decoder" to decoderFile.absolutePath,
                            "tokenizer" to tokenizerDir.absolutePath,
                            "maxTotalLen" to 512,
                            "maxNewTokens" to 512
                        )
                    )
                    constructByName(
                        "$PKG.OfflineModelConfig",
                        mapOf(
                            "qwen3Asr" to qwen3Peer,
                            "numThreads" to 4,
                            "provider" to "cpu"
                        )
                    )
                }
                modelFile.exists() && modelFile.length() < SINGLE_FILE_MODEL_SIZE_THRESHOLD -> {
                    require(File(tokens).exists()) { "tokens.txt 不存在：$tokens" }
                    logger.i("LocalAsr", "SenseVoice layout (model.int8.onnx ~${modelFile.length() / 1024 / 1024}MB)")
                    val svPeer = constructByName(
                        "$PKG.OfflineSenseVoiceModelConfig",
                        mapOf(
                            "model" to modelFile.absolutePath,
                            "language" to "zh",
                            "useInverseTextNormalization" to true
                        )
                    )
                    constructByName(
                        "$PKG.OfflineModelConfig",
                        mapOf(
                            "senseVoice" to svPeer,
                            "tokens" to tokens,
                            "numThreads" to 4,
                            "provider" to "cpu"
                        )
                    )
                }
                modelFile.exists() -> {
                    require(File(tokens).exists()) { "tokens.txt 不存在：$tokens" }
                    logger.i("LocalAsr", "FireRedASR layout=CTC (model.int8.onnx ~${modelFile.length() / 1024 / 1024}MB)")
                    val ctcPeer = constructByName(
                        "$PKG.OfflineFireRedAsrCtcModelConfig",
                        mapOf("model" to modelFile.absolutePath)
                    )
                    constructByName(
                        "$PKG.OfflineModelConfig",
                        mapOf(
                            "fireRedAsrCtc" to ctcPeer,
                            "tokens" to tokens,
                            "numThreads" to 4,
                            "provider" to "cpu"
                        )
                    )
                }
                encoderFile.exists() && decoderFile.exists() -> {
                    require(File(tokens).exists()) { "tokens.txt 不存在：$tokens" }
                    logger.i("LocalAsr", "FireRedASR layout=AED (encoder+decoder)")
                    val aedPeer = constructByName(
                        "$PKG.OfflineFireRedAsrModelConfig",
                        mapOf(
                            "encoder" to encoderFile.absolutePath,
                            "decoder" to decoderFile.absolutePath
                        )
                    )
                    constructByName(
                        "$PKG.OfflineModelConfig",
                        mapOf(
                            "fireRedAsr" to aedPeer,
                            "tokens" to tokens,
                            "numThreads" to 4,
                            "provider" to "cpu"
                        )
                    )
                }
                else -> error("找不到支持的 ASR 模型文件。modelDir=${modelDir.absolutePath}")
            }

            val recognizerConfig = constructByName(
                "$PKG.OfflineRecognizerConfig",
                mapOf(
                    "modelConfig" to modelConfig,
                    "decodingMethod" to "greedy_search"
                )
            )
            val recognizer = constructByName(
                "$PKG.OfflineRecognizer",
                // assetManager = null（用 filesDir 路径模式），config = recognizerConfig
                mapOf("config" to recognizerConfig)
            )

            val recognizerCls = Class.forName("$PKG.OfflineRecognizer")
            val streamCls = Class.forName("$PKG.OfflineStream")
            val resultCls = Class.forName("$PKG.OfflineRecognizerResult")

            return SherpaBridge(
                recognizer = recognizer,
                recognizerCls = recognizerCls,
                streamCls = streamCls,
                mResultText = resultCls.getMethod("getText"),
                logger = logger
            ).also { logger.i("LocalAsr", "sherpa-onnx OfflineRecognizer initialized (modelDir=$modelDir)") }
        }

        /**
         * 用 Kotlin 主构造按字段名传值。能跳过任何带默认值的字段；
         * 找不到对应 KParameter 的 key 静默忽略（sherpa-onnx 跨版本字段名变更时不会硬崩）。
         */
        private fun constructByName(className: String, args: Map<String, Any?>): Any {
            val cls: KClass<*> = Class.forName(className).kotlin
            val ctor = cls.primaryConstructor
                ?: error("$className has no primary constructor (是否被 ProGuard 误删？)")
            val byName: Map<KParameter, Any?> = ctor.parameters
                .mapNotNull { p ->
                    val name = p.name ?: return@mapNotNull null
                    if (args.containsKey(name)) p to args[name] else null
                }
                .toMap()
            return ctor.callBy(byName)
        }
    }
}
