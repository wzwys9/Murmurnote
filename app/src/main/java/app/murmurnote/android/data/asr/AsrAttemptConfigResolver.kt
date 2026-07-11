package app.murmurnote.android.data.asr

import java.util.Locale

/** Values captured together before a local recognition attempt starts. */
data class AsrAttemptPreferenceSnapshot(
    val engineType: AsrEngineType,
    val requestedModelId: String,
    val languageMode: AsrLanguageMode,
    val manualLanguage: String,
    val senseVoiceUseItn: Boolean,
    val localConcurrency: Int = 1
)

/**
 * Converts mutable preferences into the immutable, behavior-complete local ASR contract.
 * The engine choice is authoritative: a stale model preference can never create an
 * engine/model combination that the native bridge would interpret differently.
 */
object AsrAttemptConfigResolver {

    fun resolve(
        preferenceSnapshot: AsrAttemptPreferenceSnapshot,
        vadPresetVersion: String,
        systemLocale: Locale
    ): LocalAsrSessionConfig {
        require(vadPresetVersion.isNotBlank()) { "VAD preset version must not be blank" }

        return when (preferenceSnapshot.engineType) {
            AsrEngineType.LOCAL_SENSE_VOICE -> LocalAsrSessionConfig(
                engineType = AsrEngineType.LOCAL_SENSE_VOICE,
                modelId = AsrModelUrls.SENSE_VOICE_ID,
                options = LocalModelOptions.SenseVoice(
                    language = AsrLanguagePolicy.resolve(
                        mode = preferenceSnapshot.languageMode,
                        manualLanguage = preferenceSnapshot.manualLanguage,
                        systemLocale = systemLocale
                    ),
                    useItn = preferenceSnapshot.senseVoiceUseItn
                ),
                vadPresetVersion = vadPresetVersion,
                // Multiple recognizers each hold a full native model allocation. Keep the
                // production path single-instance until device-memory based admission exists.
                recognizerConcurrency = 1
            )

            AsrEngineType.LOCAL_QWEN3_ASR -> LocalAsrSessionConfig(
                engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                modelId = AsrModelUrls.QWEN3_ASR_ID,
                options = LocalModelOptions.Qwen3Asr(hotwordSnapshot = null),
                vadPresetVersion = vadPresetVersion,
                recognizerConcurrency = 1
            )

            AsrEngineType.CLOUD_GLM -> throw IllegalArgumentException(
                "Cloud ASR cannot be represented by LocalAsrSessionConfig"
            )
        }
    }
}
