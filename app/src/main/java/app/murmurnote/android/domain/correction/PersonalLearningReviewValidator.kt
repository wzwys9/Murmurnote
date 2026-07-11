package app.murmurnote.android.domain.correction

enum class PersonalLearningVerdict {
    ACTIVATE,
    NEEDS_MORE_EVIDENCE,
    REJECT,
}

enum class PersonalLearningConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class UntrustedPersonalLearningDecision(
    val observationId: String,
    val verdict: String,
    val confidence: String,
    val reasonCode: String,
)

data class ValidatedPersonalLearningDecision(
    val observationId: String,
    val verdict: PersonalLearningVerdict,
    val confidence: PersonalLearningConfidence,
    val reasonCode: String,
)

data class PersonalLearningReviewRequest(
    val observationId: String,
    val observedText: String,
    val replacementText: String,
    val leftContext: String,
    val rightContext: String,
    val pinyinRelation: PinyinRelation,
)

object PersonalLearningReviewValidator {
    private val allowedReasons = setOf(
        "PHONETIC_ASR_ERROR",
        "USER_TERM_FITS_CONTEXT",
        "PROPER_NOUN_FITS_CONTEXT",
        "VISUAL_SIMILARITY_ONLY",
        "NOT_AN_ASR_ERROR",
        "AMBIGUOUS_CONTEXT",
    )
    private val activationReasons = setOf(
        "PHONETIC_ASR_ERROR",
        "USER_TERM_FITS_CONTEXT",
        "PROPER_NOUN_FITS_CONTEXT",
    )

    fun validate(
        expectedObservationId: String,
        decision: UntrustedPersonalLearningDecision,
        pinyinRelation: PinyinRelation? = null,
    ): ValidatedPersonalLearningDecision? {
        if (decision.observationId != expectedObservationId) return null
        if (decision.reasonCode !in allowedReasons) return null
        val verdict = enumValues<PersonalLearningVerdict>()
            .firstOrNull { it.name == decision.verdict } ?: return null
        val confidence = enumValues<PersonalLearningConfidence>()
            .firstOrNull { it.name == decision.confidence } ?: return null
        if (
            verdict == PersonalLearningVerdict.ACTIVATE &&
            decision.reasonCode !in activationReasons
        ) {
            return null
        }
        if (
            verdict == PersonalLearningVerdict.ACTIVATE &&
            decision.reasonCode == "PHONETIC_ASR_ERROR" &&
            pinyinRelation != null &&
            pinyinRelation !in setOf(PinyinRelation.EXACT_PINYIN, PinyinRelation.NEAR_PINYIN)
        ) {
            return null
        }
        return ValidatedPersonalLearningDecision(
            observationId = decision.observationId,
            verdict = verdict,
            confidence = confidence,
            reasonCode = decision.reasonCode,
        )
    }
}
