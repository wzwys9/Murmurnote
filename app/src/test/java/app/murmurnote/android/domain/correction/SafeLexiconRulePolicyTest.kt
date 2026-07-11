package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafeLexiconRulePolicyTest {

    @Test
    fun normalizesOuterWhitespaceWithoutChangingTheExactMapping() {
        val normalized = SafeLexiconRulePolicy.normalize(
            observedText = "  木木笔记  ",
            replacementText = "  Murmurnote 声记  ",
        )

        assertEquals("木木笔记", normalized.observedText)
        assertEquals("Murmurnote 声记", normalized.replacementText)
    }

    @Test
    fun rejectsSingleCharacterGlobalRules() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("声", "生")
        }
    }

    @Test
    fun rejectsInputsLongerThanThirtyTwoUnicodeCodePoints() {
        val tooLong = "词".repeat(32) + "😀"

        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize(tooLong, "正确词语")
        }
    }

    @Test
    fun rejectsMappingsThatBecomeIdenticalAfterNormalization() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("声记", " 声记 ")
        }
    }

    @Test
    fun rejectsControlCharactersInsteadOfPersistingInvisibleRules() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("木木\n笔记", "声记")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("木木笔记", "声\u0000记")
        }
    }

    @Test
    fun rejectsUnicodeFormatAndBidirectionalControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("木木\u200B笔记", "声记应用")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("木木笔记", "声记\u202E应用")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.normalize("\u00A0\u00A0", "声记应用")
        }
    }

    @Test
    fun duplicateEnabledRuleIsReusedWithoutCreatingAnotherRow() {
        val decision = SafeLexiconRulePolicy.decideCreate(
            input = SafeLexiconRulePolicy.normalize("木木笔记", "声记应用"),
            existingGlobalRules = listOf(globalRule("one", "木木笔记", "声记应用")),
        )

        assertEquals(SafeLexiconCreateAction.REUSE_ENABLED, decision.action)
        assertEquals("one", decision.existingRuleId)
    }

    @Test
    fun duplicateDisabledRuleIsReactivatedInsteadOfDuplicated() {
        val decision = SafeLexiconRulePolicy.decideCreate(
            input = SafeLexiconRulePolicy.normalize("木木笔记", "声记应用"),
            existingGlobalRules = listOf(
                globalRule("one", "木木笔记", "声记应用", enabled = false),
            ),
        )

        assertEquals(SafeLexiconCreateAction.REACTIVATE, decision.action)
        assertEquals("one", decision.existingRuleId)
    }

    @Test
    fun disabledDuplicateIsNotReactivatedAcrossAnEnabledConflict() {
        val existing = listOf(
            globalRule("disabled", "木木笔记", "声记应用", enabled = false),
            globalRule("enabled", "木木笔记", "其他写法"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.decideCreate(
                input = SafeLexiconRulePolicy.normalize("木木笔记", "声记应用"),
                existingGlobalRules = existing,
            )
        }
    }

    @Test
    fun conflictingOrReverseGlobalMappingsAreRejected() {
        val existing = listOf(globalRule("one", "木木笔记", "声记应用"))

        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.decideCreate(
                SafeLexiconRulePolicy.normalize("木木笔记", "别的写法"),
                existing,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeLexiconRulePolicy.decideCreate(
                SafeLexiconRulePolicy.normalize("声记应用", "木木笔记"),
                existing,
            )
        }
    }

    @Test
    fun unrelatedMappingCanBeInserted() {
        val decision = SafeLexiconRulePolicy.decideCreate(
            input = SafeLexiconRulePolicy.normalize("开放人工智能", "OpenAI"),
            existingGlobalRules = listOf(globalRule("one", "木木笔记", "声记应用")),
        )

        assertEquals(SafeLexiconCreateAction.INSERT, decision.action)
        assertEquals(null, decision.existingRuleId)
    }

    private fun globalRule(
        id: String,
        observedText: String,
        replacementText: String,
        enabled: Boolean = true,
    ) = CorrectionRule(
        id = id,
        observedText = observedText,
        replacementText = replacementText,
        scope = CorrectionScope.GLOBAL,
        isEnabled = enabled,
    )
}
