package app.murmurnote.android.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPinyinTranscriberTest {
    @Test
    fun androidIcuTransliteratesCommonMandarinTextIntoSyllables() {
        assertEquals(
            listOf("sheng", "ji"),
            AndroidPinyinTranscriber().syllables("声记"),
        )
    }
}
