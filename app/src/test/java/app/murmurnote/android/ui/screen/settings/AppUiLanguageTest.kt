package app.murmurnote.android.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiLanguageTest {

    @Test
    fun emptyApplicationLocalesFollowTheSystem() {
        assertEquals(AppUiLanguage.SYSTEM, AppUiLanguage.fromLanguageTags(""))
    }

    @Test
    fun chineseLocaleVariantsSelectChinese() {
        listOf("zh", "zh-CN", "zh-Hans-CN").forEach { languageTags ->
            assertEquals(
                AppUiLanguage.CHINESE,
                AppUiLanguage.fromLanguageTags(languageTags),
            )
        }
    }

    @Test
    fun englishLocaleVariantsSelectEnglish() {
        listOf("en", "en-US", "en-GB").forEach { languageTags ->
            assertEquals(
                AppUiLanguage.ENGLISH,
                AppUiLanguage.fromLanguageTags(languageTags),
            )
        }
    }

    @Test
    fun onlyTheFirstApplicationLocaleDeterminesTheSelection() {
        assertEquals(
            AppUiLanguage.ENGLISH,
            AppUiLanguage.fromLanguageTags("en-US,zh-CN"),
        )
    }

    @Test
    fun unsupportedApplicationLocaleFallsBackToSystem() {
        assertEquals(AppUiLanguage.SYSTEM, AppUiLanguage.fromLanguageTags("ja-JP"))
    }

    @Test
    fun selectingTheCurrentLanguageDoesNotRequestAnotherUpdate() {
        assertFalse(
            shouldApplyAppUiLanguageChange(
                currentLanguage = AppUiLanguage.CHINESE,
                selectedLanguage = AppUiLanguage.CHINESE,
            )
        )
    }

    @Test
    fun selectingADifferentLanguageRequestsAnUpdate() {
        assertTrue(
            shouldApplyAppUiLanguageChange(
                currentLanguage = AppUiLanguage.CHINESE,
                selectedLanguage = AppUiLanguage.ENGLISH,
            )
        )
    }
}
