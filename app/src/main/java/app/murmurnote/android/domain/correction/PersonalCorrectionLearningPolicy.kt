package app.murmurnote.android.domain.correction

enum class PersonalCorrectionLearningState {
    PENDING_REVIEW,
    ACTIVE,
    NEEDS_MORE_EVIDENCE,
    REJECTED,
    DISABLED,
}

enum class PersonalCorrectionEventStatus {
    PENDING,
    REVIEWED,
    FAILED,
}

data class PersonalCorrectionObservationDraft(
    val observedText: String,
    val replacementText: String,
    val leftContext: String,
    val rightContext: String,
)

data class PersonalCorrectionProfile(
    val ruleId: String,
    val observedText: String,
    val replacementText: String,
    val state: PersonalCorrectionLearningState,
    val positiveEvidenceCount: Int,
    val negativeEvidenceCount: Int,
    val pinyinRelation: PinyinRelation,
    val lastVerdict: PersonalLearningVerdict?,
    val lastConfidence: PersonalLearningConfidence?,
    val lastReasonCode: String?,
    val isEnabled: Boolean,
) {
    val canBeEnabled: Boolean
        get() = state == PersonalCorrectionLearningState.DISABLED &&
            lastVerdict == PersonalLearningVerdict.ACTIVATE &&
            lastConfidence == PersonalLearningConfidence.HIGH
}

object PersonalCorrectionLearningPolicy {
    const val MAX_TERM_CODE_POINTS: Int = 32
    const val MAX_CONTEXT_SIDE_CODE_POINTS: Int = 120

    fun fromEdit(before: String, after: String): PersonalCorrectionObservationDraft? {
        val diff = SingleReplacementDiff.between(
            before = before,
            after = after,
            maxRuleCodePoints = MAX_TERM_CODE_POINTS,
        ) ?: return null
        if (!diff.eligibleForRule) return null
        return buildDraft(
            sourceText = before,
            startCodePoint = diff.startCodePoint,
            endCodePointExclusive = diff.endCodePointExclusive,
            observedText = diff.observedText,
            replacementText = diff.replacementText,
        )
    }

    fun fromMappedReplacement(
        rawText: String,
        rawStartCodePoint: Int,
        rawEndCodePointExclusive: Int,
        replacementText: String,
    ): PersonalCorrectionObservationDraft? {
        val rawLength = rawText.correctionCodePointLength()
        if (
            rawStartCodePoint < 0 ||
            rawEndCodePointExclusive <= rawStartCodePoint ||
            rawEndCodePointExclusive > rawLength
        ) {
            return null
        }
        return buildDraft(
            sourceText = rawText,
            startCodePoint = rawStartCodePoint,
            endCodePointExclusive = rawEndCodePointExclusive,
            observedText = CodePointText.slice(
                rawText,
                rawStartCodePoint,
                rawEndCodePointExclusive,
            ),
            replacementText = replacementText,
        )
    }

    private fun buildDraft(
        sourceText: String,
        startCodePoint: Int,
        endCodePointExclusive: Int,
        observedText: String,
        replacementText: String,
    ): PersonalCorrectionObservationDraft? {
        if (observedText.isBlank() || replacementText.isBlank()) return null
        if (
            observedText.correctionCodePointLength() !in 1..MAX_TERM_CODE_POINTS ||
            replacementText.correctionCodePointLength() !in 1..MAX_TERM_CODE_POINTS ||
            observedText.containsUnsafeCorrectionCodePoint() ||
            replacementText.containsUnsafeCorrectionCodePoint()
        ) {
            return null
        }
        val sourceLength = sourceText.correctionCodePointLength()
        val leftStart = (startCodePoint - MAX_CONTEXT_SIDE_CODE_POINTS).coerceAtLeast(0)
        val rightEnd = (endCodePointExclusive + MAX_CONTEXT_SIDE_CODE_POINTS)
            .coerceAtMost(sourceLength)
        return PersonalCorrectionObservationDraft(
            observedText = observedText,
            replacementText = replacementText,
            leftContext = CodePointText.slice(sourceText, leftStart, startCodePoint),
            rightContext = CodePointText.slice(sourceText, endCodePointExclusive, rightEnd),
        )
    }
}
