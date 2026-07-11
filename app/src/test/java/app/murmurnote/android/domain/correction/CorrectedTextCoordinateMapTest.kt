package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectedTextCoordinateMapTest {

    @Test
    fun mapsUnchangedCorrectedRangesBackToRawAcrossLengthChangingRules() {
        val coordinateMap = CorrectedTextCoordinateMap.create(
            rawText = "甲生记乙🙂",
            correctedText = "甲声音记录乙🙂",
            replacements = listOf(
                AppliedTextReplacement(
                    rawStartCodePoint = 1,
                    rawEndCodePointExclusive = 3,
                    originalText = "生记",
                    replacementText = "声音记录",
                ),
            ),
        )!!

        assertEquals(
            RawCodePointRange(3, 4),
            coordinateMap.rawRangeForCorrected(5, 6),
        )
        assertEquals(
            RawCodePointRange(4, 5),
            coordinateMap.rawRangeForCorrected(6, 7),
        )
    }

    @Test
    fun protectsRangesProducedByDeterministicRules() {
        val coordinateMap = CorrectedTextCoordinateMap.create(
            rawText = "甲生记乙",
            correctedText = "甲声音记录乙",
            replacements = listOf(
                AppliedTextReplacement(1, 3, "生记", "声音记录"),
            ),
        )!!

        assertNull(coordinateMap.rawRangeForCorrected(1, 3))
        assertNull(coordinateMap.rawRangeForCorrected(4, 6))
        assertEquals(
            RawCodePointRange(1, 3),
            coordinateMap.rawRangeForCorrectedIncludingReplacements(1, 5),
        )
        assertNull(coordinateMap.rawRangeForCorrectedIncludingReplacements(2, 5))
    }

    @Test
    fun rejectsCorruptOverlappingOrMismatchedAuditSpans() {
        assertNull(
            CorrectedTextCoordinateMap.create(
                rawText = "甲生记乙",
                correctedText = "甲声音乙",
                replacements = listOf(
                    AppliedTextReplacement(1, 3, "错误原词", "声音"),
                ),
            ),
        )
        assertNull(
            CorrectedTextCoordinateMap.create(
                rawText = "甲生记乙",
                correctedText = "甲声音记录乙",
                replacements = listOf(
                    AppliedTextReplacement(1, 3, "生记", "声音记录"),
                    AppliedTextReplacement(2, 3, "记", "纪"),
                ),
            ),
        )
    }
}
