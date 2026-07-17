package app.murmurnote.android.ui.screen.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal enum class AppUiLanguage(val languageTag: String) {
    SYSTEM(""),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromLanguageTags(languageTags: String): AppUiLanguage {
            val primaryTag = languageTags
                .substringBefore(',')
                .trim()
                .lowercase(Locale.ROOT)
            if (primaryTag.isEmpty()) return SYSTEM

            return entries.firstOrNull { language ->
                language.languageTag.isNotEmpty() &&
                    (primaryTag == language.languageTag ||
                        primaryTag.startsWith("${language.languageTag}-"))
            } ?: SYSTEM
        }
    }
}

internal fun currentAppUiLanguage(): AppUiLanguage = AppUiLanguage.fromLanguageTags(
    AppCompatDelegate.getApplicationLocales().toLanguageTags(),
)

internal fun shouldApplyAppUiLanguageChange(
    currentLanguage: AppUiLanguage,
    selectedLanguage: AppUiLanguage,
): Boolean = selectedLanguage != currentLanguage

internal fun setAppUiLanguage(language: AppUiLanguage) {
    // Keeps the in-app picker synchronized with Android's per-app language setting.
    // Source: https://developer.android.com/guide/topics/resources/app-languages
    val locales = if (language == AppUiLanguage.SYSTEM) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language.languageTag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}
