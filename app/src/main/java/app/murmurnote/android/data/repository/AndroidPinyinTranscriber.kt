package app.murmurnote.android.data.repository

import android.icu.text.Transliterator
import app.murmurnote.android.domain.correction.PinyinOutputParser
import app.murmurnote.android.domain.correction.PinyinTranscriber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPinyinTranscriber @Inject constructor() : PinyinTranscriber {
    private val transliterator: Transliterator? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { Transliterator.getInstance(TRANSFORM_ID) }.getOrNull()
    }

    override fun syllables(text: String): List<String>? {
        if (text.isBlank()) return null
        val current = transliterator ?: return null
        val output = synchronized(current) { current.transliterate(text) }
        return PinyinOutputParser.parse(output)
    }

    private companion object {
        const val TRANSFORM_ID = "Han-Latin; Latin-ASCII; Lower()"
    }
}
