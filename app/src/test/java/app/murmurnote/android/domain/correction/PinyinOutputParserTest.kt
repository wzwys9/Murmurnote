package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinyinOutputParserTest {

    @Test
    fun parsesToneStrippedIcuOutputIntoNormalizedSyllables() {
        assertEquals(
            listOf("sheng", "ji"),
            PinyinOutputParser.parse("Sheng ji"),
        )
    }

    @Test
    fun acceptsApostrophesAndHyphensAsSyllableBoundaries() {
        assertEquals(
            listOf("xi", "an", "sheng", "ji"),
            PinyinOutputParser.parse("xi'an-sheng ji"),
        )
    }

    @Test
    fun rejectsEmptyOrUnconvertedHanOutput() {
        assertNull(PinyinOutputParser.parse(""))
        assertNull(PinyinOutputParser.parse("声记"))
    }
}
