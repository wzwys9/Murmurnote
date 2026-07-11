package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleReplacementDiffTest {

    @Test
    fun shortContinuousSubstitutionIsEligibleForRule() {
        val diff = SingleReplacementDiff.between(
            before = "please use murmer today",
            after = "please use murmur today"
        )

        requireNotNull(diff)
        assertEquals(11, diff.startCodePoint)
        assertEquals(17, diff.endCodePointExclusive)
        assertEquals("murmer", diff.observedText)
        assertEquals("murmur", diff.replacementText)
        assertTrue(diff.eligibleForRule)
    }

    @Test
    fun insertionIsPreservedAsManualDiffButIsNotEligibleForRule() {
        val diff = SingleReplacementDiff.between(
            before = "hello world",
            after = "hello brave world"
        )

        requireNotNull(diff)
        assertEquals(6, diff.startCodePoint)
        assertEquals(6, diff.endCodePointExclusive)
        assertEquals("", diff.observedText)
        assertEquals("brave ", diff.replacementText)
        assertFalse(diff.eligibleForRule)
    }

    @Test
    fun deletionIsPreservedAsManualDiffButIsNotEligibleForRule() {
        val diff = SingleReplacementDiff.between(
            before = "hello noisy world",
            after = "hello world"
        )

        requireNotNull(diff)
        assertEquals("noisy ", diff.observedText)
        assertEquals("", diff.replacementText)
        assertFalse(diff.eligibleForRule)
    }

    @Test
    fun longContinuousSubstitutionIsNotEligibleForRule() {
        val diff = SingleReplacementDiff.between(
            before = "prefix abc suffix",
            after = "prefix xyz suffix",
            maxRuleCodePoints = 2
        )

        requireNotNull(diff)
        assertEquals("abc", diff.observedText)
        assertEquals("xyz", diff.replacementText)
        assertFalse(diff.eligibleForRule)
    }

    @Test
    fun twoSeparatedEditsDoNotProduceASingleReplacementCandidate() {
        val diff = SingleReplacementDiff.between(
            before = "alpha beta gamma",
            after = "Alpha beta Gamma"
        )

        assertNull(diff)
    }

    @Test
    fun transpositionDoesNotProduceAReusableBroadReplacement() {
        val diff = SingleReplacementDiff.between(before = "ab", after = "ba")

        assertNull(diff)
    }

    @Test
    fun unchangedTextDoesNotProduceADiff() {
        assertNull(SingleReplacementDiff.between("same", "same"))
    }

    @Test
    fun offsetsAndSlicesAreUnicodeCodePointSafe() {
        val diff = SingleReplacementDiff.between(
            before = "\ud83d\ude42\u706b\u7ea2\ud83d\udd25 done",
            after = "\ud83d\ude42FireRed done"
        )

        requireNotNull(diff)
        assertEquals(1, diff.startCodePoint)
        assertEquals(4, diff.endCodePointExclusive)
        assertEquals("\u706b\u7ea2\ud83d\udd25", diff.observedText)
        assertEquals("FireRed", diff.replacementText)
        assertTrue(diff.eligibleForRule)
    }

    @Test
    fun latinTokenExpansionDoesNotConsumePrecedingCjkText() {
        val diff = SingleReplacementDiff.between(
            before = "这是murmer项目",
            after = "这是murmur项目"
        )

        requireNotNull(diff)
        assertEquals(2, diff.startCodePoint)
        assertEquals(8, diff.endCodePointExclusive)
        assertEquals("murmer", diff.observedText)
        assertEquals("murmur", diff.replacementText)
        assertTrue(diff.eligibleForRule)
    }
}
