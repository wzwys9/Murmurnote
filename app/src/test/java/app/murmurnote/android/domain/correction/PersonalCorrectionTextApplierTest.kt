package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalCorrectionTextApplierTest {

    @Test
    fun appliesValidatedCandidatesFromRightToLeftWithUnicodeOffsets() {
        val text = "🙂生记，生记"
        val result = PersonalCorrectionTextApplier.apply(
            text,
            listOf(
                candidate("first", start = 1, end = 2),
                candidate("second", start = 4, end = 5),
            ),
        )

        assertEquals("🙂声记，声记", result)
    }

    @Test
    fun rejectsStaleRangesInsteadOfEditingDifferentText() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonalCorrectionTextApplier.apply(
                "这是胜记应用",
                listOf(candidate("stale", start = 2, end = 3)),
            )
        }
    }

    private fun candidate(id: String, start: Int, end: Int) = PersonalCorrectionCandidate(
        id = id,
        ruleId = "rule-$id",
        segmentId = 1L,
        startCodePoint = start,
        endCodePointExclusive = end,
        observedText = "生",
        replacementText = "声",
        leftContext = "",
        rightContext = "",
    )
}
