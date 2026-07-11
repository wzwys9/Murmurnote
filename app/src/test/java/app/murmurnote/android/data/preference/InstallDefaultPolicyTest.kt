package app.murmurnote.android.data.preference

import app.murmurnote.android.data.asr.AsrEngineType
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallDefaultPolicyTest {

    @Test
    fun newInstallWithoutExplicitEngineUsesLocalSenseVoice() {
        assertEquals(
            AsrEngineType.LOCAL_SENSE_VOICE,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = null,
                onboardingCompleted = false
            )
        )
    }

    @Test
    fun completedLegacyInstallWithoutExplicitEngineKeepsCloudGlm() {
        assertEquals(
            AsrEngineType.CLOUD_GLM,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = null,
                onboardingCompleted = true
            )
        )
    }

    @Test
    fun explicitEngineAlwaysWinsOverInstallDefault() {
        assertEquals(
            AsrEngineType.LOCAL_QWEN3_ASR,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = AsrEngineType.LOCAL_QWEN3_ASR.name,
                onboardingCompleted = true
            )
        )
        assertEquals(
            AsrEngineType.CLOUD_GLM,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = AsrEngineType.CLOUD_GLM.name,
                onboardingCompleted = false
            )
        )
    }

    @Test
    fun legacyAndUnknownExplicitEngineValuesUseSafeCompatibilityParsing() {
        assertEquals(
            AsrEngineType.LOCAL_SENSE_VOICE,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = "LOCAL_FIRE_RED_ASR",
                onboardingCompleted = true
            )
        )
        assertEquals(
            AsrEngineType.CLOUD_GLM,
            AsrInstallDefaultPolicy.resolve(
                explicitEngine = "UNRECOGNIZED_ENGINE",
                onboardingCompleted = false
            )
        )
    }

    @Test
    fun newInstallWithoutExplicitAiChoiceStartsOptedOut() {
        assertEquals(
            false,
            AiInstallDefaultPolicy.resolve(
                explicitEnabled = null,
                onboardingCompleted = false
            )
        )
    }

    @Test
    fun completedLegacyInstallWithoutExplicitAiChoiceKeepsPreviousEnabledBehavior() {
        assertEquals(
            true,
            AiInstallDefaultPolicy.resolve(
                explicitEnabled = null,
                onboardingCompleted = true
            )
        )
    }

    @Test
    fun explicitAiChoiceAlwaysWinsOverInstallDefault() {
        assertEquals(
            true,
            AiInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                onboardingCompleted = false
            )
        )
        assertEquals(
            false,
            AiInstallDefaultPolicy.resolve(
                explicitEnabled = false,
                onboardingCompleted = true
            )
        )
    }

    @Test
    fun safeLexiconStartsDisabledEvenWhenAnLlmApiIsConfigured() {
        assertEquals(
            false,
            SafeLexiconInstallDefaultPolicy.resolve(
                explicitEnabled = null,
                llmApiConfigured = true,
            ),
        )
    }

    @Test
    fun safeLexiconCannotBeEnabledWithoutTheCurrentLlmApiKey() {
        assertEquals(
            false,
            SafeLexiconInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                llmApiConfigured = false,
            ),
        )
        assertEquals(
            true,
            SafeLexiconInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                llmApiConfigured = true,
            ),
        )
        assertEquals(
            false,
            SafeLexiconInstallDefaultPolicy.resolve(
                explicitEnabled = false,
                llmApiConfigured = true,
            ),
        )
    }

    @Test
    fun configuringAnApiKeyDoesNotRestoreAStaleEnabledState() {
        assertEquals(
            false,
            SafeLexiconInstallDefaultPolicy.resolveAfterApiKeyUpdate(
                explicitEnabled = true,
                wasConfigured = false,
                isConfigured = true,
            ),
        )
        assertEquals(
            true,
            SafeLexiconInstallDefaultPolicy.resolveAfterApiKeyUpdate(
                explicitEnabled = true,
                wasConfigured = true,
                isConfigured = true,
            ),
        )
        assertEquals(
            false,
            SafeLexiconInstallDefaultPolicy.resolveAfterApiKeyUpdate(
                explicitEnabled = true,
                wasConfigured = true,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun personalCorrectionRequiresApiDisclosureAndExplicitEnable() {
        assertEquals(
            false,
            PersonalCorrectionInstallDefaultPolicy.resolve(
                explicitEnabled = null,
                llmApiConfigured = true,
                disclosureAccepted = true,
            ),
        )
        assertEquals(
            false,
            PersonalCorrectionInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                llmApiConfigured = false,
                disclosureAccepted = true,
            ),
        )
        assertEquals(
            false,
            PersonalCorrectionInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                llmApiConfigured = true,
                disclosureAccepted = false,
            ),
        )
        assertEquals(
            true,
            PersonalCorrectionInstallDefaultPolicy.resolve(
                explicitEnabled = true,
                llmApiConfigured = true,
                disclosureAccepted = true,
            ),
        )
    }
}
