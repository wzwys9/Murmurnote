package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCorrectionEngineTest {

    private val engine = DeterministicCorrectionEngine()

    @Test
    fun exactRuleProducesCorrectedTextAndStructuredRecordWithoutChangingRawText() {
        val rawText = String(charArrayOf('\u706b', '\u7ea2', '\u8bed', '\u97f3'))
        val rule = CorrectionRule(
            id = "rule-1",
            observedText = "\u706b\u7ea2",
            replacementText = "FireRed",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(rawText, recordingId = "recording-1", rules = listOf(rule))

        assertEquals("\u706b\u7ea2\u8bed\u97f3", rawText)
        assertEquals(rawText, result.rawText)
        assertNotSame(rawText, result.correctedText)
        assertEquals("FireRed\u8bed\u97f3", result.correctedText)
        assertEquals(
            CorrectionRecord(
                sourceRuleId = "rule-1",
                rawStartCodePoint = 0,
                rawEndCodePointExclusive = 2,
                originalText = "\u706b\u7ea2",
                replacementText = "FireRed",
                scope = CorrectionScope.GLOBAL,
                decision = CorrectionDecision.APPLIED,
                decisionReason = CorrectionDecisionReason.EXACT_TEXT_RULE_APPLIED
            ),
            result.records.single()
        )
    }

    @Test
    fun recordingRuleWinsOverGlobalRuleForTheSameRawRange() {
        val recordingRule = CorrectionRule(
            id = "recording-rule",
            observedText = "murmer",
            replacementText = "Murmurnote",
            scope = CorrectionScope.RECORDING,
            scopeId = "recording-1"
        )
        val globalRule = CorrectionRule(
            id = "global-rule",
            observedText = "murmer",
            replacementText = "murmur",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(
            rawText = "use murmer today",
            recordingId = "recording-1",
            rules = listOf(globalRule, recordingRule)
        )

        assertEquals("use Murmurnote today", result.correctedText)
        assertEquals(CorrectionDecision.APPLIED, result.records.single { it.sourceRuleId == "recording-rule" }.decision)
        assertEquals(CorrectionDecision.REJECTED, result.records.single { it.sourceRuleId == "global-rule" }.decision)
        assertEquals(
            CorrectionDecisionReason.OVERLAPS_HIGHER_PRIORITY,
            result.records.single { it.sourceRuleId == "global-rule" }.decisionReason
        )
    }

    @Test
    fun recordingRuleDoesNotApplyToAnotherRecording() {
        val recordingRule = CorrectionRule(
            id = "recording-rule",
            observedText = "murmer",
            replacementText = "Murmurnote",
            scope = CorrectionScope.RECORDING,
            scopeId = "recording-1"
        )
        val globalRule = CorrectionRule(
            id = "global-rule",
            observedText = "murmer",
            replacementText = "murmur",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(
            rawText = "murmer",
            recordingId = "recording-2",
            rules = listOf(recordingRule, globalRule)
        )

        assertEquals("murmur", result.correctedText)
        assertEquals(listOf("global-rule"), result.records.map { it.sourceRuleId })
    }

    @Test
    fun longerObservedTextWinsBetweenRulesInTheSameScope() {
        val shortRule = CorrectionRule(
            id = "short",
            observedText = "\u706b\u7ea2",
            replacementText = "\u706b\u7ea2\u724c",
            scope = CorrectionScope.GLOBAL
        )
        val longRule = CorrectionRule(
            id = "long",
            observedText = "\u706b\u7ea2\u8bed\u97f3",
            replacementText = "FireRedASR",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(
            rawText = "\u706b\u7ea2\u8bed\u97f3",
            recordingId = "recording-1",
            rules = listOf(shortRule, longRule)
        )

        assertEquals("FireRedASR", result.correctedText)
        assertEquals(CorrectionDecision.APPLIED, result.records.single { it.sourceRuleId == "long" }.decision)
        assertEquals(CorrectionDecision.REJECTED, result.records.single { it.sourceRuleId == "short" }.decision)
    }

    @Test
    fun equallyRankedConflictingRulesAreRejectedWithoutFallingBack() {
        val first = CorrectionRule(
            id = "first",
            observedText = "sense voice",
            replacementText = "SenseVoice",
            scope = CorrectionScope.RECORDING,
            scopeId = "recording-1"
        )
        val second = CorrectionRule(
            id = "second",
            observedText = "sense voice",
            replacementText = "Sense Voice",
            scope = CorrectionScope.RECORDING,
            scopeId = "recording-1"
        )
        val fallback = CorrectionRule(
            id = "fallback",
            observedText = "sense voice",
            replacementText = "sensevoice",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(
            rawText = "sense voice",
            recordingId = "recording-1",
            rules = listOf(fallback, second, first)
        )

        assertEquals("sense voice", result.correctedText)
        assertEquals(3, result.records.size)
        assertTrue(result.records.all { it.decision == CorrectionDecision.REJECTED })
        assertTrue(result.records.all { it.decisionReason == CorrectionDecisionReason.CONFLICTING_RULES })
    }

    @Test
    fun equalPriorityOverlappingRangesAreRejected() {
        val first = CorrectionRule(
            id = "first",
            observedText = "abc",
            replacementText = "one",
            scope = CorrectionScope.GLOBAL
        )
        val second = CorrectionRule(
            id = "second",
            observedText = "bcd",
            replacementText = "two",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct("abcd", recordingId = null, rules = listOf(first, second))

        assertEquals("abcd", result.correctedText)
        assertTrue(result.records.all { it.decision == CorrectionDecision.REJECTED })
        assertTrue(result.records.all { it.decisionReason == CorrectionDecisionReason.CONFLICTING_RULES })
    }

    @Test
    fun unicodeOffsetsAndMatchingUseCodePointsInsteadOfUtf16Units() {
        val rule = CorrectionRule(
            id = "emoji-rule",
            observedText = "\u706b\u7ea2\ud83d\udd25",
            replacementText = "FireRed",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct(
            rawText = "\ud83d\ude42\u706b\u7ea2\ud83d\udd25\ud83d\ude42",
            recordingId = null,
            rules = listOf(rule)
        )

        assertEquals("\ud83d\ude42FireRed\ud83d\ude42", result.correctedText)
        assertEquals(1, result.records.single().rawStartCodePoint)
        assertEquals(4, result.records.single().rawEndCodePointExclusive)
        assertEquals("\u706b\u7ea2\ud83d\udd25", result.records.single().originalText)
    }

    @Test
    fun independentMatchesAreAppliedUsingRawOffsets() {
        val rule = CorrectionRule(
            id = "rule",
            observedText = "x",
            replacementText = "long replacement",
            scope = CorrectionScope.GLOBAL
        )

        val result = engine.correct("x \ud83d\ude42 x", recordingId = null, rules = listOf(rule))

        assertEquals("long replacement \ud83d\ude42 long replacement", result.correctedText)
        assertEquals(listOf(0, 4), result.records.map { it.rawStartCodePoint })
        assertTrue(result.records.all { it.decision == CorrectionDecision.APPLIED })
    }

    @Test
    fun ruleOrderDoesNotChangeResultOrRecords() {
        val shortRule = CorrectionRule(
            id = "short",
            observedText = "ab",
            replacementText = "short",
            scope = CorrectionScope.GLOBAL
        )
        val longRule = CorrectionRule(
            id = "long",
            observedText = "abc",
            replacementText = "long",
            scope = CorrectionScope.GLOBAL
        )

        val forward = engine.correct("abc", null, listOf(shortRule, longRule))
        val reverse = engine.correct("abc", null, listOf(longRule, shortRule))

        assertEquals(forward, reverse)
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rule = CorrectionRule(
            id = "disabled",
            observedText = "wrong",
            replacementText = "right",
            scope = CorrectionScope.GLOBAL,
            isEnabled = false
        )

        val result = engine.correct("wrong", null, listOf(rule))

        assertEquals("wrong", result.correctedText)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun automaticRulesRejectEmptyObservedOrReplacementText() {
        assertThrows(IllegalArgumentException::class.java) {
            CorrectionRule(
                id = "empty-observed",
                observedText = "",
                replacementText = "value",
                scope = CorrectionScope.GLOBAL
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CorrectionRule(
                id = "deletion",
                observedText = "value",
                replacementText = "",
                scope = CorrectionScope.GLOBAL
            )
        }
    }

    @Test
    fun recordingScopeRequiresAnIdAndGlobalScopeRejectsOne() {
        assertThrows(IllegalArgumentException::class.java) {
            CorrectionRule(
                id = "missing-recording",
                observedText = "wrong",
                replacementText = "right",
                scope = CorrectionScope.RECORDING
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CorrectionRule(
                id = "unexpected-recording",
                observedText = "wrong",
                replacementText = "right",
                scope = CorrectionScope.GLOBAL,
                scopeId = "recording-1"
            )
        }
    }
}
