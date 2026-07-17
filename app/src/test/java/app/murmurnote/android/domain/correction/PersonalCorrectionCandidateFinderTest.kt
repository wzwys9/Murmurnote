package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorrectionCandidateFinderTest {

    @Test
    fun findsOnlyEnabledContextualRulesAndKeepsBoundedContext() {
        val text = "左".repeat(100) + "生记" + "右".repeat(100)
        val rules = listOf(
            rule("learned", "生", "声", CorrectionMatchMode.CONTEXTUAL_LLM),
            rule("exact", "记", "纪", CorrectionMatchMode.EXACT_TEXT),
            rule("disabled", "应用", "APP", CorrectionMatchMode.CONTEXTUAL_LLM, enabled = false),
        )

        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 9L,
            text = text,
            rules = rules,
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("learned", candidate.ruleId)
        assertEquals(100, candidate.startCodePoint)
        assertEquals(101, candidate.endCodePointExclusive)
        assertEquals(80, candidate.leftContext.codePointCount(0, candidate.leftContext.length))
        assertEquals(80, candidate.rightContext.codePointCount(0, candidate.rightContext.length))
        assertTrue(candidate.rightContext.startsWith("记"))
    }

    @Test
    fun capsEachSegmentAtSixCandidatesInStableTextOrder() {
        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 3L,
            text = "生".repeat(10),
            rules = listOf(rule("learned", "生", "声", CorrectionMatchMode.CONTEXTUAL_LLM)),
        )

        assertEquals(6, candidates.size)
        assertEquals((0 until 6).toList(), candidates.map { it.startCodePoint })
        assertEquals(candidates.map { it.id }.distinct().size, candidates.size)
    }

    @Test
    fun learnedContextRanksTheRelevantOccurrenceAheadOfCommonSingleCharacters() {
        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 4L,
            text = "生".repeat(6) + "这是生记应用",
            rules = listOf(rule("learned", "生", "声", CorrectionMatchMode.CONTEXTUAL_LLM)),
            contextHints = mapOf(
                "learned" to PersonalCorrectionContextHint(
                    leftContext = "这是",
                    rightContext = "记应用",
                ),
            ),
        )

        assertEquals(6, candidates.size)
        assertEquals(8, candidates.first().startCodePoint)
        assertTrue(candidates.any { it.startCodePoint == 8 })
    }

    @Test
    fun pinyinRelationBreaksATieWithoutDirectlyCreatingCandidates() {
        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 5L,
            text = "甲乙",
            rules = listOf(
                rule("first", "甲", "申", CorrectionMatchMode.CONTEXTUAL_LLM),
                rule("phonetic", "乙", "一", CorrectionMatchMode.CONTEXTUAL_LLM),
            ),
            contextHints = mapOf(
                "first" to PersonalCorrectionContextHint("", "", PinyinRelation.NOT_PHONETIC),
                "phonetic" to PersonalCorrectionContextHint("", "", PinyinRelation.EXACT_PINYIN),
            ),
        )

        assertEquals(listOf("phonetic", "first"), candidates.map { it.ruleId })
    }

    @Test
    fun userDefinedCandidatesAreNotCrowdedOutByLearnedCandidates() {
        val learnedRules = (0 until 6).map { index ->
            rule(
                id = "learned-$index",
                observed = "生",
                replacement = "声$index",
                mode = CorrectionMatchMode.CONTEXTUAL_LLM,
                origin = CorrectionRuleOrigin.PERSONAL_LEARNING,
            )
        }
        val userRule = rule(
            id = "user",
            observed = "生",
            replacement = "声记",
            mode = CorrectionMatchMode.CONTEXTUAL_LLM,
            origin = CorrectionRuleOrigin.USER_DEFINED,
        )

        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 7L,
            text = "生",
            rules = learnedRules + userRule,
        )

        assertEquals(6, candidates.size)
        assertEquals("user", candidates.first().ruleId)
        assertEquals(CorrectionRuleOrigin.USER_DEFINED, candidates.first().ruleOrigin)
    }

    @Test
    fun pathologicalSegmentLengthFailsClosedBeforeScanningRules() {
        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 5L,
            text = "生".repeat(10_001),
            rules = listOf(rule("learned", "生", "声", CorrectionMatchMode.CONTEXTUAL_LLM)),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun ignoresCorruptRulesWithUnsafeOrOversizedTerms() {
        val candidates = PersonalCorrectionCandidateFinder.find(
            segmentId = 6L,
            text = "生记" + "长".repeat(40),
            rules = listOf(
                rule(
                    id = "unsafe",
                    observed = "生",
                    replacement = "声\u202E",
                    mode = CorrectionMatchMode.CONTEXTUAL_LLM,
                ),
                rule(
                    id = "oversized",
                    observed = "长".repeat(33),
                    replacement = "短",
                    mode = CorrectionMatchMode.CONTEXTUAL_LLM,
                ),
            ),
        )

        assertTrue(candidates.isEmpty())
    }

    private fun rule(
        id: String,
        observed: String,
        replacement: String,
        mode: CorrectionMatchMode,
        enabled: Boolean = true,
        origin: CorrectionRuleOrigin = CorrectionRuleOrigin.USER_DEFINED,
    ) = CorrectionRule(
        id = id,
        observedText = observed,
        replacementText = replacement,
        matchMode = mode,
        origin = origin,
        scope = CorrectionScope.GLOBAL,
        isEnabled = enabled,
    )
}
