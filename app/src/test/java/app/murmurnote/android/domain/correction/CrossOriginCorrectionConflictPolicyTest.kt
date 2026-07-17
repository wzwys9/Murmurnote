package app.murmurnote.android.domain.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossOriginCorrectionConflictPolicyTest {

    @Test
    fun personalLearningRulesCannotUseUnconditionalReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            CorrectionRule(
                id = "invalid-learned-rule",
                observedText = "生记",
                replacementText = "声记",
                matchMode = CorrectionMatchMode.EXACT_TEXT,
                scope = CorrectionScope.GLOBAL,
                origin = CorrectionRuleOrigin.PERSONAL_LEARNING,
            )
        }
    }

    @Test
    fun sameObservedTextConflictsEvenWhenTheReplacementMatches() {
        assertTrue(
            CrossOriginCorrectionConflictPolicy.conflicts(
                userDefined = userRule("生记", "声记"),
                learned = learnedRule("生记", "声记"),
            ),
        )
    }

    @Test
    fun learnedRuleCannotRewriteAUserDefinedResult() {
        assertTrue(
            CrossOriginCorrectionConflictPolicy.conflicts(
                userDefined = userRule("木木笔记", "声记应用"),
                learned = learnedRule("声记应用", "Murmurnote"),
            ),
        )
    }

    @Test
    fun learnedRuleCannotProduceAUserDefinedObservedTerm() {
        assertTrue(
            CrossOriginCorrectionConflictPolicy.conflicts(
                userDefined = userRule("木木笔记", "声记应用"),
                learned = learnedRule("Murmurnote", "木木笔记"),
            ),
        )
    }

    @Test
    fun aSharedReplacementAloneDoesNotConflict() {
        assertFalse(
            CrossOriginCorrectionConflictPolicy.conflicts(
                userDefined = userRule("木木笔记", "声记应用"),
                learned = learnedRule("生记应用", "声记应用"),
            ),
        )
    }

    @Test
    fun unrelatedRulesDoNotConflict() {
        assertFalse(
            CrossOriginCorrectionConflictPolicy.conflicts(
                userDefined = userRule("木木笔记", "声记应用"),
                learned = learnedRule("开放人工智能", "OpenAI"),
            ),
        )
    }

    private fun userRule(observed: String, replacement: String) = CorrectionRule(
        id = "user-$observed",
        observedText = observed,
        replacementText = replacement,
        matchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
        scope = CorrectionScope.GLOBAL,
        origin = CorrectionRuleOrigin.USER_DEFINED,
    )

    private fun learnedRule(observed: String, replacement: String) = CorrectionRule(
        id = "learned-$observed",
        observedText = observed,
        replacementText = replacement,
        matchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
        scope = CorrectionScope.GLOBAL,
        origin = CorrectionRuleOrigin.PERSONAL_LEARNING,
    )
}
