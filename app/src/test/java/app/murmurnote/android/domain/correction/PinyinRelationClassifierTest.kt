package app.murmurnote.android.domain.correction

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinRelationClassifierTest {

    @Test
    fun identicalNormalizedSyllablesAreExactPinyin() {
        assertEquals(
            PinyinRelation.EXACT_PINYIN,
            PinyinRelationClassifier.classify(
                observedSyllables = listOf("sheng", "ji"),
                replacementSyllables = listOf("sheng", "ji"),
            ),
        )
    }

    @Test
    fun oneSmallSyllableDifferenceIsNearPinyin() {
        assertEquals(
            PinyinRelation.NEAR_PINYIN,
            PinyinRelationClassifier.classify(
                observedSyllables = listOf("sen", "ji"),
                replacementSyllables = listOf("sheng", "ji"),
            ),
        )
    }

    @Test
    fun multipleOrLargeDifferencesAreNotPhonetic() {
        assertEquals(
            PinyinRelation.NOT_PHONETIC,
            PinyinRelationClassifier.classify(
                observedSyllables = listOf("mu", "mu"),
                replacementSyllables = listOf("sheng", "ji"),
            ),
        )
        assertEquals(
            PinyinRelation.NOT_PHONETIC,
            PinyinRelationClassifier.classify(
                observedSyllables = listOf("sen", "qi"),
                replacementSyllables = listOf("sheng", "ji"),
            ),
        )
    }

    @Test
    fun missingOrUnalignedSyllablesAreUnavailableOrNotPhonetic() {
        assertEquals(
            PinyinRelation.UNAVAILABLE,
            PinyinRelationClassifier.classify(null, listOf("sheng", "ji")),
        )
        assertEquals(
            PinyinRelation.UNAVAILABLE,
            PinyinRelationClassifier.classify(emptyList(), listOf("sheng", "ji")),
        )
        assertEquals(
            PinyinRelation.NOT_PHONETIC,
            PinyinRelationClassifier.classify(listOf("sheng"), listOf("sheng", "ji")),
        )
    }
}
