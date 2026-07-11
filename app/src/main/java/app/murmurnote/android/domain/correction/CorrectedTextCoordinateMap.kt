package app.murmurnote.android.domain.correction

data class AppliedTextReplacement(
    val rawStartCodePoint: Int,
    val rawEndCodePointExclusive: Int,
    val originalText: String,
    val replacementText: String,
)

data class RawCodePointRange(
    val startCodePoint: Int,
    val endCodePointExclusive: Int,
)

data class CorrectedCodePointRange(
    val startCodePoint: Int,
    val endCodePointExclusive: Int,
)

/**
 * Maps unchanged ranges in derived corrected text back to the immutable model output.
 * Text produced by an earlier deterministic rule is deliberately not projectable, which keeps
 * that rule at a higher priority than contextual correction.
 */
class CorrectedTextCoordinateMap private constructor(
    private val rawLength: Int,
    private val correctedLength: Int,
    private val spans: List<ReplacementSpan>,
) {
    fun rawRangeForCorrected(
        startCodePoint: Int,
        endCodePointExclusive: Int,
    ): RawCodePointRange? {
        if (
            startCodePoint < 0 ||
            endCodePointExclusive <= startCodePoint ||
            endCodePointExclusive > correctedLength
        ) {
            return null
        }
        if (
            spans.any { span ->
                startCodePoint < span.correctedEndCodePointExclusive &&
                    span.correctedStartCodePoint < endCodePointExclusive
            }
        ) {
            return null
        }

        val precedingDelta = spans
            .asSequence()
            .takeWhile { it.correctedEndCodePointExclusive <= startCodePoint }
            .sumOf { it.correctedLength - it.rawLength }
        val rawStart = startCodePoint - precedingDelta
        val rawEnd = endCodePointExclusive - precedingDelta
        if (rawStart < 0 || rawEnd > rawLength) return null
        return RawCodePointRange(rawStart, rawEnd)
    }

    fun rawRangeForCorrectedIncludingReplacements(
        startCodePoint: Int,
        endCodePointExclusive: Int,
    ): RawCodePointRange? {
        if (
            startCodePoint < 0 ||
            endCodePointExclusive <= startCodePoint ||
            endCodePointExclusive > correctedLength
        ) {
            return null
        }
        val rawStart = rawBoundaryForCorrected(startCodePoint) ?: return null
        val rawEnd = rawBoundaryForCorrected(endCodePointExclusive) ?: return null
        if (rawEnd <= rawStart) return null
        return RawCodePointRange(rawStart, rawEnd)
    }

    fun correctedRangeForRawReplacement(
        rawStartCodePoint: Int,
        rawEndCodePointExclusive: Int,
    ): CorrectedCodePointRange? = spans
        .singleOrNull {
            it.rawStartCodePoint == rawStartCodePoint &&
                it.rawEndCodePointExclusive == rawEndCodePointExclusive
        }
        ?.let {
            CorrectedCodePointRange(
                startCodePoint = it.correctedStartCodePoint,
                endCodePointExclusive = it.correctedEndCodePointExclusive,
            )
        }

    private fun rawBoundaryForCorrected(correctedOffset: Int): Int? {
        if (correctedOffset !in 0..correctedLength) return null
        if (
            spans.any {
                correctedOffset > it.correctedStartCodePoint &&
                    correctedOffset < it.correctedEndCodePointExclusive
            }
        ) {
            return null
        }
        val precedingDelta = spans
            .asSequence()
            .takeWhile { it.correctedEndCodePointExclusive <= correctedOffset }
            .sumOf { it.correctedLength - it.rawLength }
        return (correctedOffset - precedingDelta).takeIf { it in 0..rawLength }
    }

    private data class ReplacementSpan(
        val rawStartCodePoint: Int,
        val rawEndCodePointExclusive: Int,
        val correctedStartCodePoint: Int,
        val correctedEndCodePointExclusive: Int,
    ) {
        val rawLength: Int = rawEndCodePointExclusive - rawStartCodePoint
        val correctedLength: Int =
            correctedEndCodePointExclusive - correctedStartCodePoint
    }

    companion object {
        fun create(
            rawText: String,
            correctedText: String,
            replacements: List<AppliedTextReplacement>,
        ): CorrectedTextCoordinateMap? {
            val rawLength = rawText.correctionCodePointLength()
            val sorted = replacements.sortedWith(
                compareBy(
                    AppliedTextReplacement::rawStartCodePoint,
                    AppliedTextReplacement::rawEndCodePointExclusive,
                ),
            )
            var rawCursor = 0
            var correctedDelta = 0
            val spans = mutableListOf<ReplacementSpan>()
            val reconstructed = buildString {
                for (replacement in sorted) {
                    if (
                        replacement.rawStartCodePoint < rawCursor ||
                        replacement.rawEndCodePointExclusive <=
                        replacement.rawStartCodePoint ||
                        replacement.rawEndCodePointExclusive > rawLength
                    ) {
                        return null
                    }
                    val original = CodePointText.slice(
                        rawText,
                        replacement.rawStartCodePoint,
                        replacement.rawEndCodePointExclusive,
                    )
                    if (original != replacement.originalText) return null
                    append(CodePointText.slice(rawText, rawCursor, replacement.rawStartCodePoint))
                    append(replacement.replacementText)

                    val correctedStart = replacement.rawStartCodePoint + correctedDelta
                    val correctedEnd = correctedStart +
                        replacement.replacementText.correctionCodePointLength()
                    spans += ReplacementSpan(
                        rawStartCodePoint = replacement.rawStartCodePoint,
                        rawEndCodePointExclusive = replacement.rawEndCodePointExclusive,
                        correctedStartCodePoint = correctedStart,
                        correctedEndCodePointExclusive = correctedEnd,
                    )
                    correctedDelta += replacement.replacementText.correctionCodePointLength() -
                        (replacement.rawEndCodePointExclusive - replacement.rawStartCodePoint)
                    rawCursor = replacement.rawEndCodePointExclusive
                }
                append(CodePointText.slice(rawText, rawCursor, rawLength))
            }
            if (reconstructed != correctedText) return null
            return CorrectedTextCoordinateMap(
                rawLength = rawLength,
                correctedLength = correctedText.correctionCodePointLength(),
                spans = spans,
            )
        }
    }
}
