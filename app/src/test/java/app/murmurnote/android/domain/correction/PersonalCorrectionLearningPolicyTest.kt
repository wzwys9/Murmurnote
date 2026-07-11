package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorrectionLearningPolicyTest {

    @Test
    fun capturesOneExplicitReplacementWithBoundedCodePointContext() {
        val before = "左".repeat(140) + "生记" + "右".repeat(140)
        val after = "左".repeat(140) + "声记" + "右".repeat(140)

        val draft = PersonalCorrectionLearningPolicy.fromEdit(before, after)

        requireNotNull(draft)
        assertEquals("生", draft.observedText)
        assertEquals("声", draft.replacementText)
        assertEquals(120, draft.leftContext.codePointCount(0, draft.leftContext.length))
        assertEquals(120, draft.rightContext.codePointCount(0, draft.rightContext.length))
        assertTrue(draft.leftContext.all { it == '左' })
        assertEquals("记" + "右".repeat(119), draft.rightContext)
    }

    @Test
    fun preservesSupplementaryUnicodeWithoutSplittingSurrogatePairs() {
        val before = "🙂".repeat(130) + "生记" + "🔥".repeat(130)
        val after = "🙂".repeat(130) + "声记" + "🔥".repeat(130)

        val draft = PersonalCorrectionLearningPolicy.fromEdit(before, after)

        requireNotNull(draft)
        assertEquals("🙂".repeat(120), draft.leftContext)
        assertEquals("记" + "🔥".repeat(119), draft.rightContext)
    }

    @Test
    fun ignoresInsertionsDeletionsAndSeparatedEdits() {
        assertNull(PersonalCorrectionLearningPolicy.fromEdit("你好世界", "你好新世界"))
        assertNull(PersonalCorrectionLearningPolicy.fromEdit("你好新世界", "你好世界"))
        assertNull(PersonalCorrectionLearningPolicy.fromEdit("甲乙丙丁", "申乙丙电"))
    }

    @Test
    fun ignoresUnsafeOrOversizedMappingsInsteadOfPersistingThem() {
        assertNull(PersonalCorrectionLearningPolicy.fromEdit("生\u200B记", "声记"))
        assertNull(PersonalCorrectionLearningPolicy.fromEdit("生记", "声\u202E记"))
        assertNull(
            PersonalCorrectionLearningPolicy.fromEdit(
                before = "甲".repeat(33),
                after = "乙".repeat(33),
            ),
        )
    }
}
