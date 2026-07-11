package app.murmurnote.android.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class AsrAttemptConfigResolverTest {

    @Test
    fun `system language resolves Chinese locale for SenseVoice`() {
        val config = resolveSense(locale = Locale.SIMPLIFIED_CHINESE)

        assertEquals(AsrEngineType.LOCAL_SENSE_VOICE, config.engineType)
        assertEquals(AsrModelUrls.SENSE_VOICE_ID, config.modelId)
        assertEquals(
            LocalModelOptions.SenseVoice(language = "zh", useItn = true),
            config.options
        )
    }

    @Test
    fun `system language resolves English locale for SenseVoice`() {
        val config = resolveSense(locale = Locale.US)

        assertEquals(
            LocalModelOptions.SenseVoice(language = "en", useItn = true),
            config.options
        )
    }

    @Test
    fun `system language falls back to auto for unsupported locale`() {
        val config = resolveSense(locale = Locale.GERMANY)

        assertEquals(
            LocalModelOptions.SenseVoice(language = AsrLanguagePolicy.AUTO, useItn = true),
            config.options
        )
    }

    @Test
    fun `Qwen always uses Qwen model and auto options`() {
        val config = AsrAttemptConfigResolver.resolve(
            preferenceSnapshot = AsrAttemptPreferenceSnapshot(
                engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                requestedModelId = AsrModelUrls.SENSE_VOICE_ID,
                languageMode = AsrLanguageMode.MANUAL,
                manualLanguage = "ja",
                senseVoiceUseItn = false
            ),
            vadPresetVersion = "vad-v1",
            systemLocale = Locale.JAPAN
        )

        assertEquals(AsrModelUrls.QWEN3_ASR_ID, config.modelId)
        assertEquals(LocalModelOptions.Qwen3Asr(hotwordSnapshot = null), config.options)
    }

    @Test
    fun `cloud engine is rejected by local resolver`() {
        assertThrows(IllegalArgumentException::class.java) {
            AsrAttemptConfigResolver.resolve(
                preferenceSnapshot = AsrAttemptPreferenceSnapshot(
                    engineType = AsrEngineType.CLOUD_GLM,
                    requestedModelId = AsrModelUrls.SENSE_VOICE_ID,
                    languageMode = AsrLanguageMode.SYSTEM,
                    manualLanguage = AsrLanguagePolicy.AUTO,
                    senseVoiceUseItn = true
                ),
                vadPresetVersion = "vad-v1",
                systemLocale = Locale.US
            )
        }
    }

    @Test
    fun `behavior-affecting setting changes fingerprint while old snapshot stays frozen`() {
        var useItn = true
        val first = resolveSense(locale = Locale.US, useItn = useItn)

        useItn = false
        val second = resolveSense(locale = Locale.US, useItn = useItn)

        assertNotEquals(first.configFingerprint, second.configFingerprint)
        assertEquals(
            LocalModelOptions.SenseVoice(language = "en", useItn = true),
            first.options
        )
        assertEquals(
            LocalModelOptions.SenseVoice(language = "en", useItn = false),
            second.options
        )
    }

    @Test
    fun `local recognizers are forced to one for mobile stability`() {
        val one = resolveSense(locale = Locale.US, concurrency = 1)
        val three = resolveSense(locale = Locale.US, concurrency = 3)
        val qwen = AsrAttemptConfigResolver.resolve(
            preferenceSnapshot = AsrAttemptPreferenceSnapshot(
                engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                requestedModelId = AsrModelUrls.QWEN3_ASR_ID,
                languageMode = AsrLanguageMode.AUTO,
                manualLanguage = "zh",
                senseVoiceUseItn = true,
                localConcurrency = 3
            ),
            vadPresetVersion = "vad-v1",
            systemLocale = Locale.US
        )

        assertEquals(1, one.recognizerConcurrency)
        assertEquals(1, three.recognizerConcurrency)
        assertEquals(one.configFingerprint, three.configFingerprint)
        assertEquals(1, qwen.recognizerConcurrency)
    }

    private fun resolveSense(
        locale: Locale,
        useItn: Boolean = true,
        concurrency: Int = 1
    ): LocalAsrSessionConfig = AsrAttemptConfigResolver.resolve(
        preferenceSnapshot = AsrAttemptPreferenceSnapshot(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            requestedModelId = AsrModelUrls.QWEN3_ASR_ID,
            languageMode = AsrLanguageMode.SYSTEM,
            manualLanguage = "yue",
            senseVoiceUseItn = useItn,
            localConcurrency = concurrency
        ),
        vadPresetVersion = "vad-v1",
        systemLocale = locale
    )
}
