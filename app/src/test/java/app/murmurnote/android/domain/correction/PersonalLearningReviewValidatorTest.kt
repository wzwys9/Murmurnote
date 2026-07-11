package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalLearningReviewValidatorTest {

    @Test
    fun acceptsOnlyTheRequestedObservationAndAllowlistedEnums() {
        val result = PersonalLearningReviewValidator.validate(
            expectedObservationId = "event-1",
            decision = UntrustedPersonalLearningDecision(
                observationId = "event-1",
                verdict = "ACTIVATE",
                confidence = "HIGH",
                reasonCode = "PHONETIC_ASR_ERROR",
            ),
        )

        requireNotNull(result)
        assertEquals(PersonalLearningVerdict.ACTIVATE, result.verdict)
        assertEquals(PersonalLearningConfidence.HIGH, result.confidence)
        assertEquals("PHONETIC_ASR_ERROR", result.reasonCode)
    }

    @Test
    fun rejectsMismatchedIdsAndPromptInjectedEnumValues() {
        assertNull(
            PersonalLearningReviewValidator.validate(
                "event-1",
                UntrustedPersonalLearningDecision(
                    "another-event",
                    "ACTIVATE",
                    "HIGH",
                    "PHONETIC_ASR_ERROR",
                ),
            ),
        )
        assertNull(
            PersonalLearningReviewValidator.validate(
                "event-1",
                UntrustedPersonalLearningDecision(
                    "event-1",
                    "ACTIVATE_AND_REWRITE_DATABASE",
                    "HIGH",
                    "PHONETIC_ASR_ERROR",
                ),
            ),
        )
        assertNull(
            PersonalLearningReviewValidator.validate(
                "event-1",
                UntrustedPersonalLearningDecision(
                    "event-1",
                    "ACTIVATE",
                    "CERTAIN",
                    "IGNORE_PREVIOUS_INSTRUCTIONS",
                ),
            ),
        )
        assertNull(
            PersonalLearningReviewValidator.validate(
                "event-1",
                UntrustedPersonalLearningDecision(
                    "event-1",
                    "ACTIVATE",
                    "HIGH",
                    "VISUAL_SIMILARITY_ONLY",
                ),
            ),
        )
    }

    @Test
    fun rejectsPhoneticActivationThatContradictsTheLocalPinyinSignal() {
        assertNull(
            PersonalLearningReviewValidator.validate(
                expectedObservationId = "event-1",
                decision = UntrustedPersonalLearningDecision(
                    observationId = "event-1",
                    verdict = "ACTIVATE",
                    confidence = "HIGH",
                    reasonCode = "PHONETIC_ASR_ERROR",
                ),
                pinyinRelation = PinyinRelation.NOT_PHONETIC,
            ),
        )
    }
}
