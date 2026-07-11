package app.murmurnote.android.domain.correction

fun interface PinyinTranscriber {
    fun syllables(text: String): List<String>?
}

object PinyinOutputParser {
    private val asciiSyllable = Regex("[a-z]+")

    fun parse(transliterated: String): List<String>? {
        if (transliterated.isBlank() || transliterated.hasHanCodePoint()) return null
        return asciiSyllable.findAll(transliterated.lowercase())
            .map { it.value }
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    private fun String.hasHanCodePoint(): Boolean {
        var charOffset = 0
        while (charOffset < length) {
            val codePoint = Character.codePointAt(this, charOffset)
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) return true
            charOffset += Character.charCount(codePoint)
        }
        return false
    }
}
