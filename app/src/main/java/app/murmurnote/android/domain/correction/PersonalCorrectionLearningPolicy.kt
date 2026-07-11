package app.murmurnote.android.domain.correction

data class PersonalCorrectionObservationDraft(
    val observedText: String,
    val replacementText: String,
    val leftContext: String,
    val rightContext: String,
)

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
        if (diff.observedText.isBlank() || diff.replacementText.isBlank()) return null
        if (
            diff.observedText.containsUnsafeCorrectionCodePoint() ||
            diff.replacementText.containsUnsafeCorrectionCodePoint()
        ) {
            return null
        }

        val beforeLength = before.correctionCodePointLength()
        val leftStart = (diff.startCodePoint - MAX_CONTEXT_SIDE_CODE_POINTS).coerceAtLeast(0)
        val rightEnd = (diff.endCodePointExclusive + MAX_CONTEXT_SIDE_CODE_POINTS)
            .coerceAtMost(beforeLength)
        return PersonalCorrectionObservationDraft(
            observedText = diff.observedText,
            replacementText = diff.replacementText,
            leftContext = CodePointText.slice(before, leftStart, diff.startCodePoint),
            rightContext = CodePointText.slice(before, diff.endCodePointExclusive, rightEnd),
        )
    }
}
