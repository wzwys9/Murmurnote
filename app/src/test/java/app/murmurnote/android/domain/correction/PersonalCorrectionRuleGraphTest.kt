package app.murmurnote.android.domain.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorrectionRuleGraphTest {

    @Test
    fun rejectsDirectReverseAndLongerReplacementCycles() {
        assertTrue(
            PersonalCorrectionRuleGraph.wouldCreateCycle(
                observedText = "乙",
                replacementText = "甲",
                activeRules = listOf(rule("one", "甲", "乙")),
            ),
        )
        assertTrue(
            PersonalCorrectionRuleGraph.wouldCreateCycle(
                observedText = "丙",
                replacementText = "甲",
                activeRules = listOf(
                    rule("one", "甲", "乙"),
                    rule("two", "乙", "丙"),
                ),
            ),
        )
    }

    @Test
    fun acceptsAnAcyclicPersonalReplacement() {
        assertFalse(
            PersonalCorrectionRuleGraph.wouldCreateCycle(
                observedText = "丙",
                replacementText = "丁",
                activeRules = listOf(
                    rule("one", "甲", "乙"),
                    rule("two", "乙", "丙"),
                ),
            ),
        )
    }

    private fun rule(id: String, observed: String, replacement: String) = CorrectionRule(
        id = id,
        observedText = observed,
        replacementText = replacement,
        matchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
        scope = CorrectionScope.GLOBAL,
    )
}
