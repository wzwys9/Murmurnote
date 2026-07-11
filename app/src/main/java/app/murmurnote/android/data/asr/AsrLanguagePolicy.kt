package app.murmurnote.android.data.asr

import java.util.Locale

enum class AsrLanguageMode {
    SYSTEM,
    AUTO,
    MANUAL;

    companion object {
        fun parse(value: String?): AsrLanguageMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

object AsrLanguagePolicy {
    const val AUTO = "auto"

    val manualLanguages: Set<String> = linkedSetOf(AUTO, "zh", "en", "ja", "ko", "yue")

    fun resolve(
        mode: AsrLanguageMode,
        manualLanguage: String,
        systemLocale: Locale
    ): String = when (mode) {
        AsrLanguageMode.SYSTEM -> when (systemLocale.language.lowercase(Locale.ROOT)) {
            "zh" -> "zh"
            "en" -> "en"
            else -> AUTO
        }
        AsrLanguageMode.AUTO -> AUTO
        AsrLanguageMode.MANUAL -> normalizeManualLanguage(manualLanguage) ?: AUTO
    }

    fun normalizeManualLanguage(value: String): String? =
        value.trim().lowercase(Locale.ROOT).takeIf(manualLanguages::contains)
}
