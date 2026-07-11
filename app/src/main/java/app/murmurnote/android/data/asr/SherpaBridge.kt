package app.murmurnote.android.data.asr

import app.murmurnote.android.util.Logger
import java.io.File
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

internal enum class SherpaModelKind {
    SENSE_VOICE,
    QWEN3_ASR
}

internal data class SherpaBridgeParameters(
    val modelKind: SherpaModelKind,
    val modelArguments: Map<String, Any?>,
    val offlineModelArguments: Map<String, Any?>,
    val metadataForLogging: String
)

/** Builds sherpa constructor parameters without loading the AAR or JNI. */
internal object SherpaBridgeParameterResolver {

    fun resolve(
        modelDir: File,
        config: LocalAsrSessionConfig,
        requestedThreads: Int
    ): SherpaBridgeParameters = when (config.engineType) {
        AsrEngineType.LOCAL_SENSE_VOICE -> resolveSenseVoice(modelDir, config, requestedThreads)
        AsrEngineType.LOCAL_QWEN3_ASR -> resolveQwen(modelDir, config)
        AsrEngineType.CLOUD_GLM -> throw IllegalArgumentException(
            "Cloud ASR cannot initialize a local sherpa bridge"
        )
    }

    private fun resolveSenseVoice(
        modelDir: File,
        config: LocalAsrSessionConfig,
        requestedThreads: Int
    ): SherpaBridgeParameters {
        require(config.modelId == AsrModelUrls.SENSE_VOICE_ID) {
            "SenseVoice engine requires the SenseVoice model"
        }
        val options = config.options as? LocalModelOptions.SenseVoice
            ?: throw IllegalArgumentException("SenseVoice engine requires SenseVoice options")
        val model = requireRegularFile(modelDir, "model.int8.onnx")
        val tokens = requireRegularFile(modelDir, "tokens.txt")
        val threads = requestedThreads.coerceIn(1, MAX_SENSE_VOICE_THREADS)
        return SherpaBridgeParameters(
            modelKind = SherpaModelKind.SENSE_VOICE,
            modelArguments = linkedMapOf(
                "model" to model.absolutePath,
                "language" to options.language,
                "useInverseTextNormalization" to options.useItn
            ),
            offlineModelArguments = linkedMapOf(
                "tokens" to tokens.absolutePath,
                "numThreads" to threads,
                "provider" to "cpu"
            ),
            metadataForLogging = buildString {
                append("fingerprint=")
                append(config.configFingerprint.take(FINGERPRINT_LOG_PREFIX_LENGTH))
                append(" model=")
                append(config.modelId)
                append(" engine=")
                append(config.engineType.name)
                append(" language=")
                append(options.language)
                append(" itn=")
                append(options.useItn)
            }
        )
    }

    private fun resolveQwen(
        modelDir: File,
        config: LocalAsrSessionConfig
    ): SherpaBridgeParameters {
        require(config.modelId == AsrModelUrls.QWEN3_ASR_ID) {
            "Qwen3-ASR engine requires the Qwen3-ASR model"
        }
        val options = config.options as? LocalModelOptions.Qwen3Asr
            ?: throw IllegalArgumentException("Qwen3-ASR engine requires Qwen3-ASR options")
        val convFrontend = requireRegularFile(modelDir, "conv_frontend.onnx")
        val encoder = requireRegularFile(modelDir, "encoder.int8.onnx")
        val decoder = requireRegularFile(modelDir, "decoder.int8.onnx")
        val tokenizer = File(modelDir, "tokenizer")
        require(tokenizer.isDirectory && !tokenizer.list().isNullOrEmpty()) {
            "Qwen3-ASR tokenizer directory is missing or empty"
        }
        val serializedHotwords = serializeQwenHotwords(options.hotwordSnapshot)
        return SherpaBridgeParameters(
            modelKind = SherpaModelKind.QWEN3_ASR,
            modelArguments = linkedMapOf(
                "convFrontend" to convFrontend.absolutePath,
                "encoder" to encoder.absolutePath,
                "decoder" to decoder.absolutePath,
                "tokenizer" to tokenizer.absolutePath,
                "maxTotalLen" to 512,
                "maxNewTokens" to 512,
                "temperature" to 1e-6f,
                "topP" to 0.8f,
                "seed" to 42,
                "hotwords" to serializedHotwords
            ),
            offlineModelArguments = linkedMapOf(
                // Qwen's memory footprint requires one recognizer/decode at a time.
                "numThreads" to 1,
                "provider" to "cpu"
            ),
            metadataForLogging = buildString {
                append("fingerprint=")
                append(config.configFingerprint.take(FINGERPRINT_LOG_PREFIX_LENGTH))
                append(" model=")
                append(config.modelId)
                append(" engine=")
                append(config.engineType.name)
                append(" language=auto hotwordsConfigured=")
                append(options.hotwordSnapshot != null)
            }
        )
    }

    /**
     * sherpa-onnx 1.12.39 documents Qwen hotwords as UTF-8 entries separated by an ASCII comma.
     * There is no escaping syntax, so ambiguous entries are rejected instead of guessed.
     */
    private fun serializeQwenHotwords(snapshot: HotwordSnapshot?): String {
        if (snapshot == null) return ""
        snapshot.hotwords.forEach { hotword ->
            require(hotword.isNotEmpty()) { "Qwen hotword must not be empty" }
            require(hotword.none { it == ',' || it.isISOControl() }) {
                "Qwen hotword contains an unsupported delimiter or control character"
            }
        }
        return snapshot.hotwords.joinToString(separator = ",")
    }

    private fun requireRegularFile(modelDir: File, relativePath: String): File =
        File(modelDir, relativePath).also { file ->
            require(file.isFile && file.length() > 0L) {
                "Required local ASR model file is missing or empty: $relativePath"
            }
        }

    private const val MAX_SENSE_VOICE_THREADS = 3
    private const val FINGERPRINT_LOG_PREFIX_LENGTH = 12
}

/**
 * 反射桥接 sherpa-onnx Kotlin/JNI API。本地 ASR 引擎专用。
 *
 * 设计目的：sherpa-onnx 的 AAR 没塞到 app/libs/ 时整个 app 仍能编译运行；
 * 只有真正调用本地引擎那一刻才感知到缺失。所有反射细节集中在这一文件里，
 * LocalAsrEngine 看到的是干净的 decode(samples) → String。
 *
 * 反射目标 API（sherpa-onnx Android 1.12.x，包名 com.k2fsa.sherpa.onnx）：
 *   - data class OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder, tokenizer, ...)
 *   - data class OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization)
 *   - data class OfflineModelConfig(... qwen3Asr, senseVoice, tokens, numThreads, provider ...)
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
 * 用 kotlin-reflect 的 primaryConstructor.callBy(...) 按字段名传值。影响识别行为的字段会验证
 * 1.12.39 构造参数确实存在，避免旧 AAR 静默忽略语言、ITN 或热词。
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

        /** Temporary compatibility for callers that have not adopted attempt snapshots yet. */
        @Deprecated("Pass a frozen LocalAsrSessionConfig")
        fun create(modelDir: File, numThreads: Int, logger: Logger): SherpaBridge {
            val isQwen = File(modelDir, "conv_frontend.onnx").isFile
            val config = if (isQwen) {
                LocalAsrSessionConfig(
                    engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                    modelId = AsrModelUrls.QWEN3_ASR_ID,
                    options = LocalModelOptions.Qwen3Asr(hotwordSnapshot = null),
                    vadPresetVersion = "legacy-unspecified"
                )
            } else {
                LocalAsrSessionConfig(
                    engineType = AsrEngineType.LOCAL_SENSE_VOICE,
                    modelId = AsrModelUrls.SENSE_VOICE_ID,
                    options = LocalModelOptions.SenseVoice(language = "zh", useItn = true),
                    vadPresetVersion = "legacy-unspecified"
                )
            }
            return create(modelDir, config, numThreads, logger)
        }

        /** Constructs an OfflineRecognizer from one frozen attempt config. */
        fun create(
            modelDir: File,
            config: LocalAsrSessionConfig,
            numThreads: Int,
            logger: Logger
        ): SherpaBridge {
            val parameters = SherpaBridgeParameterResolver.resolve(
                modelDir = modelDir,
                config = config,
                requestedThreads = numThreads
            )
            logger.i("LocalAsr", "initializing sherpa bridge ${parameters.metadataForLogging}")

            val (modelClass, offlineModelKey) = when (parameters.modelKind) {
                SherpaModelKind.SENSE_VOICE ->
                    "$PKG.OfflineSenseVoiceModelConfig" to "senseVoice"
                SherpaModelKind.QWEN3_ASR ->
                    "$PKG.OfflineQwen3AsrModelConfig" to "qwen3Asr"
            }
            val modelPeer = constructByName(
                className = modelClass,
                args = parameters.modelArguments,
                requiredArgumentNames = parameters.modelArguments.keys
            )
            val offlineModelArguments = linkedMapOf<String, Any?>(
                offlineModelKey to modelPeer
            ).apply {
                putAll(parameters.offlineModelArguments)
            }
            val modelConfig = constructByName(
                className = "$PKG.OfflineModelConfig",
                args = offlineModelArguments,
                requiredArgumentNames = offlineModelArguments.keys
            )

            val recognizerConfig = constructByName(
                className = "$PKG.OfflineRecognizerConfig",
                args = mapOf(
                    "modelConfig" to modelConfig,
                    "decodingMethod" to "greedy_search"
                ),
                requiredArgumentNames = setOf("modelConfig", "decodingMethod")
            )
            val recognizer = constructByName(
                className = "$PKG.OfflineRecognizer",
                // assetManager = null（用 filesDir 路径模式），config = recognizerConfig
                args = mapOf("config" to recognizerConfig),
                requiredArgumentNames = setOf("config")
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
            ).also {
                logger.i(
                    "LocalAsr",
                    "sherpa bridge initialized ${parameters.metadataForLogging} " +
                        "threads=${parameters.offlineModelArguments["numThreads"]}"
                )
            }
        }

        /**
         * Uses named primary-constructor parameters and fails closed if the installed AAR cannot
         * represent a behavior-affecting value from the frozen config.
         */
        private fun constructByName(
            className: String,
            args: Map<String, Any?>,
            requiredArgumentNames: Set<String> = emptySet()
        ): Any {
            val cls: KClass<*> = Class.forName(className).kotlin
            val ctor = cls.primaryConstructor
                ?: error("$className has no primary constructor (是否被 ProGuard 误删？)")
            val availableNames = ctor.parameters.mapNotNull(KParameter::name).toSet()
            val missingRequiredArguments = requiredArgumentNames - availableNames
            require(missingRequiredArguments.isEmpty()) {
                "$className does not support required frozen ASR parameters: " +
                    missingRequiredArguments.sorted().joinToString()
            }
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
