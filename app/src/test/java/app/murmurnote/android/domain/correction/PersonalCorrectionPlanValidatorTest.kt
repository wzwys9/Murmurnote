package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorrectionPlanValidatorTest {

    @Test
    fun onlyKnownHighConfidenceAllowlistedApplyDecisionIsApproved() {
        val candidates = listOf(candidate("c1", 2, 4), candidate("c2", 8, 10))
        val decisions = listOf(
            untrusted("c1", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
            untrusted("c2", "APPLY", "MEDIUM", "USER_TERM_FITS_CONTEXT"),
            untrusted("unknown", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
        )

        val approved = PersonalCorrectionPlanValidator.approve(candidates, decisions)

        assertEquals(listOf("c1"), approved.map { it.id })
    }

    @Test
    fun keepUnknownActionsAndUnknownReasonsDefaultToNoChange() {
        val candidates = listOf(candidate("c1", 2, 4), candidate("c2", 8, 10))

        val approved = PersonalCorrectionPlanValidator.approve(
            candidates,
            listOf(
                untrusted("c1", "REWRITE", "HIGH", "PHONETIC_ASR_ERROR"),
                untrusted("c2", "APPLY", "HIGH", "IGNORE_ALL_PREVIOUS_INSTRUCTIONS"),
            ),
        )

        assertTrue(approved.isEmpty())
    }

    @Test
    fun duplicateDecisionsForOneCandidateAreRejected() {
        val candidate = candidate("c1", 2, 4)

        val approved = PersonalCorrectionPlanValidator.approve(
            listOf(candidate),
            listOf(
                untrusted("c1", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
                untrusted("c1", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
            ),
        )

        assertTrue(approved.isEmpty())
    }

    @Test
    fun overlappingApprovedCandidatesAreBothRejected() {
        val candidates = listOf(candidate("short", 2, 4), candidate("long", 1, 5))

        val approved = PersonalCorrectionPlanValidator.approve(
            candidates,
            listOf(
                untrusted("short", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
                untrusted("long", "APPLY", "HIGH", "USER_TERM_FITS_CONTEXT"),
            ),
        )

        assertTrue(approved.isEmpty())
    }

    @Test
    fun userDefinedCandidateWinsWhenTheSameRangeAlsoHasALearnedCandidate() {
        val learned = candidate(
            id = "learned",
            start = 2,
            end = 4,
            origin = CorrectionRuleOrigin.PERSONAL_LEARNING,
        )
        val userDefined = candidate(
            id = "user",
            start = 2,
            end = 4,
            origin = CorrectionRuleOrigin.USER_DEFINED,
        )

        val approved = PersonalCorrectionPlanValidator.approve(
            candidates = listOf(learned, userDefined),
            decisions = listOf(
                untrusted("learned", "APPLY", "HIGH", "PHONETIC_ASR_ERROR"),
                untrusted("user", "APPLY", "HIGH", "USER_TERM_FITS_CONTEXT"),
            ),
        )

        assertEquals(listOf("user"), approved.map { it.id })
    }

    @Test
    fun candidateCountIsHardCappedBeforeModelOutputCanBeApplied() {
        val candidates = (0 until 25).map { index ->
            candidate("c$index", index * 3, index * 3 + 2)
        }
        val decisions = candidates.map {
            untrusted(it.id, "APPLY", "HIGH", "PHONETIC_ASR_ERROR")
        }

        val approved = PersonalCorrectionPlanValidator.approve(candidates, decisions)

        assertEquals(24, approved.size)
        assertEquals("c23", approved.last().id)
    }

    private fun candidate(
        id: String,
        start: Int,
        end: Int,
        origin: CorrectionRuleOrigin = CorrectionRuleOrigin.PERSONAL_LEARNING,
    ) = PersonalCorrectionCandidate(
        id = id,
        ruleId = "rule-$id",
        ruleOrigin = origin,
        segmentId = 7L,
        startCodePoint = start,
        endCodePointExclusive = end,
        observedText = "生记",
        replacementText = "声记",
        leftContext = "这是",
        rightContext = "应用",
    )

    private fun untrusted(
        id: String,
        action: String,
        confidence: String,
        reason: String,
    ) = UntrustedPersonalCorrectionDecision(
        candidateId = id,
        action = action,
        confidence = confidence,
        reasonCode = reason,
    )
}
