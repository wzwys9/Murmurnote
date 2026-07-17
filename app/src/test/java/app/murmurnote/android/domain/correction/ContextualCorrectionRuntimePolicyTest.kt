package app.murmurnote.android.domain.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualCorrectionRuntimePolicyTest {

    @Test
    fun missingLlmConfigurationSkipsAllContextualRules() {
        val policy = ContextualCorrectionRuntimePolicy.resolve(
            customDictionaryEnabled = true,
            personalLearningEnabled = true,
            llmConfigured = false,
        )

        assertFalse(policy.canReviewLearning)
        assertFalse(policy.canReviewCandidates)
        assertFalse(policy.includes(CorrectionRuleOrigin.USER_DEFINED))
        assertFalse(policy.includes(CorrectionRuleOrigin.PERSONAL_LEARNING))
    }

    @Test
    fun eachMasterSwitchControlsOnlyItsOwnContextualRuleSource() {
        val customOnly = ContextualCorrectionRuntimePolicy.resolve(
            customDictionaryEnabled = true,
            personalLearningEnabled = false,
            llmConfigured = true,
        )
        val learningOnly = ContextualCorrectionRuntimePolicy.resolve(
            customDictionaryEnabled = false,
            personalLearningEnabled = true,
            llmConfigured = true,
        )

        assertTrue(customOnly.canReviewCandidates)
        assertTrue(customOnly.includes(CorrectionRuleOrigin.USER_DEFINED))
        assertFalse(customOnly.includes(CorrectionRuleOrigin.PERSONAL_LEARNING))
        assertFalse(customOnly.canReviewLearning)

        assertTrue(learningOnly.canReviewCandidates)
        assertFalse(learningOnly.includes(CorrectionRuleOrigin.USER_DEFINED))
        assertTrue(learningOnly.includes(CorrectionRuleOrigin.PERSONAL_LEARNING))
        assertTrue(learningOnly.canReviewLearning)
    }
}
