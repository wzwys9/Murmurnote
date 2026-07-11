package app.murmurnote.android.data.asr

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface LocalModelOptions {

    data class SenseVoice(
        val language: String,
        val useItn: Boolean
    ) : LocalModelOptions

    data class Qwen3Asr(
        val hotwordSnapshot: HotwordSnapshot?
    ) : LocalModelOptions
}

object CloudAsrModels {
    const val GLM_ASR_2512 = "glm-asr-2512"
}

/** Immutable vocabulary captured before a Qwen recognition session starts. */
class HotwordSnapshot(hotwords: List<String>) {
    val hotwords: List<String> = hotwords.toList()

    override fun equals(other: Any?): Boolean =
        this === other || other is HotwordSnapshot && hotwords == other.hotwords

    override fun hashCode(): Int = hotwords.hashCode()

    override fun toString(): String = "HotwordSnapshot(hotwords=$hotwords)"
}

data class LocalAsrSessionConfig(
    val engineType: AsrEngineType,
    val modelId: String,
    val options: LocalModelOptions,
    val vadPresetVersion: String,
    val recognizerConcurrency: Int = 1
) {
    init {
        require(recognizerConcurrency in 1..3) {
            "Local ASR recognizer concurrency must be between one and three"
        }
        val optionsMatchEngine = when (engineType) {
            AsrEngineType.LOCAL_SENSE_VOICE -> options is LocalModelOptions.SenseVoice
            AsrEngineType.LOCAL_QWEN3_ASR -> options is LocalModelOptions.Qwen3Asr
            AsrEngineType.CLOUD_GLM -> false
        }
        require(optionsMatchEngine) {
            val optionsType = when (options) {
                is LocalModelOptions.SenseVoice -> "SenseVoice"
                is LocalModelOptions.Qwen3Asr -> "Qwen3Asr"
            }
            "Local ASR engine $engineType is incompatible with $optionsType options"
        }
        require(engineType != AsrEngineType.LOCAL_QWEN3_ASR || recognizerConcurrency == 1) {
            "Qwen3-ASR is restricted to one recognizer"
        }
    }

    val configSnapshotJson: String = canonicalSnapshotJson()

    val configFingerprint: String = sha256Hex(configSnapshotJson)

    fun toProvenance(): AsrProvenance = AsrProvenance(
        engineType = engineType,
        modelId = modelId,
        configFingerprint = configFingerprint,
        configSnapshotJson = configSnapshotJson,
        vadPresetVersion = vadPresetVersion
    )

    private fun canonicalSnapshotJson(): String = buildString {
        append('{')
        append("\"schemaVersion\":")
        append(CONFIG_SNAPSHOT_SCHEMA_VERSION)
        append(",\"engineType\":")
        appendJsonString(engineType.name)
        append(",\"modelId\":")
        appendJsonString(modelId)
        append(",\"recognizerConcurrency\":")
        append(recognizerConcurrency)
        append(",\"options\":{")
        when (val modelOptions = options) {
            is LocalModelOptions.SenseVoice -> {
                append("\"type\":\"SENSE_VOICE\",\"language\":")
                appendJsonString(modelOptions.language)
                append(",\"useItn\":")
                append(modelOptions.useItn)
            }

            is LocalModelOptions.Qwen3Asr -> {
                append("\"type\":\"QWEN3_ASR\",\"hotwordSnapshot\":")
                val hotwordSnapshot = modelOptions.hotwordSnapshot
                if (hotwordSnapshot == null) {
                    append("null")
                } else {
                    append("{\"hotwords\":[")
                    hotwordSnapshot.hotwords.forEachIndexed { index, hotword ->
                        if (index > 0) append(',')
                        appendJsonString(hotword)
                    }
                    append("]}")
                }
            }
        }
        append('}')
        append(",\"vadPresetVersion\":")
        appendJsonString(vadPresetVersion)
        append('}')
    }

    private companion object {
        const val CONFIG_SNAPSHOT_SCHEMA_VERSION = 1
    }
}

/**
 * Immutable cloud-ASR identity captured for an attempt. Credentials and request contents are
 * deliberately absent: they do not affect cache compatibility and must never enter provenance.
 */
data class CloudAsrSessionConfig(
    val modelId: String,
    val vadPresetVersion: String,
    val endpointFingerprint: String
) {
    init {
        require(modelId.isNotBlank()) { "Cloud ASR model id must not be blank" }
        require(vadPresetVersion.isNotBlank()) { "VAD preset version must not be blank" }
        require(endpointFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Cloud endpoint fingerprint must be lowercase SHA-256"
        }
    }

    val configSnapshotJson: String = buildString {
        append('{')
        append("\"schemaVersion\":1")
        append(",\"engineType\":\"CLOUD_GLM\"")
        append(",\"modelId\":")
        appendJsonString(modelId)
        append(",\"endpointFingerprint\":")
        appendJsonString(endpointFingerprint)
        append(",\"vadPresetVersion\":")
        appendJsonString(vadPresetVersion)
        append('}')
    }

    val configFingerprint: String = sha256Hex(configSnapshotJson)

    fun toProvenance(): AsrProvenance = AsrProvenance(
        engineType = AsrEngineType.CLOUD_GLM,
        modelId = modelId,
        configFingerprint = configFingerprint,
        configSnapshotJson = configSnapshotJson,
        vadPresetVersion = vadPresetVersion
    )

    companion object {
        fun fromBaseUrl(
            modelId: String,
            vadPresetVersion: String,
            baseUrl: String
        ): CloudAsrSessionConfig = CloudAsrSessionConfig(
            modelId = modelId,
            vadPresetVersion = vadPresetVersion,
            endpointFingerprint = sha256Hex(baseUrl.trim())
        )
    }
}

/** Frozen cloud request values. [toString] never exposes credentials or endpoint text. */
class CloudAsrRequestConfig(
    val sessionConfig: CloudAsrSessionConfig,
    val baseUrl: String,
    val apiKey: String
) {
    init {
        require(baseUrl.isNotBlank()) { "Cloud ASR base URL must not be blank" }
        require(apiKey.isNotBlank()) { "Cloud ASR API key must not be blank" }
        require(
            sessionConfig.endpointFingerprint == sha256Hex(baseUrl.trim())
        ) { "Cloud ASR endpoint does not match its frozen provenance" }
    }

    override fun toString(): String =
        "CloudAsrRequestConfig(modelId=${sessionConfig.modelId}, endpoint=<redacted>, apiKey=<redacted>)"
}

data class AsrProvenance(
    val engineType: AsrEngineType,
    val modelId: String,
    val configFingerprint: String,
    val configSnapshotJson: String,
    val vadPresetVersion: String
)

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < JSON_CONTROL_CHARACTER_LIMIT) {
                    append("\\u")
                    append(HEX_DIGITS[(character.code ushr 12) and 0x0f])
                    append(HEX_DIGITS[(character.code ushr 8) and 0x0f])
                    append(HEX_DIGITS[(character.code ushr 4) and 0x0f])
                    append(HEX_DIGITS[character.code and 0x0f])
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
    val result = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val unsignedByte = byte.toInt() and 0xff
        result[index * 2] = HEX_DIGITS[unsignedByte ushr 4]
        result[index * 2 + 1] = HEX_DIGITS[unsignedByte and 0x0f]
    }
    return String(result)
}

private const val JSON_CONTROL_CHARACTER_LIMIT = 0x20
private const val HEX_DIGITS = "0123456789abcdef"
