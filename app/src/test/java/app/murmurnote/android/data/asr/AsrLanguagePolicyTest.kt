package app.murmurnote.android.data.asr

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrLanguagePolicyTest {

    @Test
    fun systemModeMapsChineseLocaleToChinese() {
        assertEquals(
            "zh",
            AsrLanguagePolicy.resolve(
                mode = AsrLanguageMode.SYSTEM,
                manualLanguage = "auto",
                systemLocale = Locale.SIMPLIFIED_CHINESE
            )
        )
    }

    @Test
    fun systemModeMapsEnglishLocaleToEnglish() {
        assertEquals(
            "en",
            AsrLanguagePolicy.resolve(
                mode = AsrLanguageMode.SYSTEM,
                manualLanguage = "auto",
                systemLocale = Locale.US
            )
        )
    }

    @Test
    fun systemModeUsesAutoForEveryOtherLocale() {
        assertEquals(
            "auto",
            AsrLanguagePolicy.resolve(
                mode = AsrLanguageMode.SYSTEM,
                manualLanguage = "zh",
                systemLocale = Locale.JAPAN
            )
        )
    }

    @Test
    fun autoModeIgnoresLocaleAndManualPreference() {
        assertEquals(
            "auto",
            AsrLanguagePolicy.resolve(
                mode = AsrLanguageMode.AUTO,
                manualLanguage = "zh",
                systemLocale = Locale.CHINA
            )
        )
    }

    @Test
    fun manualModeNormalizesEverySupportedLanguage() {
        val inputs = mapOf(
            " AUTO " to "auto",
            "ZH" to "zh",
            " en " to "en",
            "Ja" to "ja",
            "KO" to "ko",
            "YUE" to "yue"
        )

        inputs.forEach { (input, expected) ->
            assertEquals(expected, AsrLanguagePolicy.normalizeManualLanguage(input))
            assertEquals(
                expected,
                AsrLanguagePolicy.resolve(
                    mode = AsrLanguageMode.MANUAL,
                    manualLanguage = input,
                    systemLocale = Locale.US
                )
            )
        }
    }

    @Test
    fun manualNormalizationRejectsUnsupportedOrBlankValues() {
        assertNull(AsrLanguagePolicy.normalizeManualLanguage("fr"))
        assertNull(AsrLanguagePolicy.normalizeManualLanguage(""))
        assertNull(AsrLanguagePolicy.normalizeManualLanguage("zh-CN"))
    }

    @Test
    fun invalidPersistedManualValueResolvesSafelyToAuto() {
        assertEquals(
            "auto",
            AsrLanguagePolicy.resolve(
                mode = AsrLanguageMode.MANUAL,
                manualLanguage = "invalid",
                systemLocale = Locale.CHINA
            )
        )
    }

    @Test
    fun languageModeParserFallsBackToSystem() {
        assertEquals(AsrLanguageMode.MANUAL, AsrLanguageMode.parse("MANUAL"))
        assertEquals(AsrLanguageMode.SYSTEM, AsrLanguageMode.parse("unknown"))
        assertEquals(AsrLanguageMode.SYSTEM, AsrLanguageMode.parse(null))
    }
}
