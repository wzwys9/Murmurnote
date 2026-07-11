package app.murmurnote.android.domain.correction

data class SingleReplacementDiff(
    val startCodePoint: Int,
    val endCodePointExclusive: Int,
    val observedText: String,
    val replacementText: String,
    val eligibleForRule: Boolean
) {
    companion object {
        const val DEFAULT_MAX_RULE_CODE_POINTS: Int = 32

        fun between(
            before: String,
            after: String,
            maxRuleCodePoints: Int = DEFAULT_MAX_RULE_CODE_POINTS
        ): SingleReplacementDiff? {
            require(maxRuleCodePoints > 0) { "Maximum rule length must be positive" }
            if (before == after) return null

            val beforeCodePoints = CodePointText.values(before)
            val afterCodePoints = CodePointText.values(after)
            val prefixLength = commonPrefixLength(beforeCodePoints, afterCodePoints)
            val suffixLength = commonSuffixLength(
                beforeCodePoints,
                afterCodePoints,
                prefixLength
            )
            val minimalBeforeEnd = beforeCodePoints.size - suffixLength
            val minimalAfterEnd = afterCodePoints.size - suffixLength
            val beforeMiddle = beforeCodePoints.copyOfRange(prefixLength, minimalBeforeEnd)
            val afterMiddle = afterCodePoints.copyOfRange(prefixLength, minimalAfterEnd)

            if (hasCommonCodePoint(beforeMiddle, afterMiddle)) return null

            val boundaries = expandSubstitutionToToken(
                before = beforeCodePoints,
                after = afterCodePoints,
                start = prefixLength,
                beforeEnd = minimalBeforeEnd,
                afterEnd = minimalAfterEnd
            )
            val observedText = CodePointText.slice(before, boundaries.start, boundaries.beforeEnd)
            val replacementText = CodePointText.slice(after, boundaries.start, boundaries.afterEnd)
            val isShortSubstitution =
                observedText.isNotEmpty() &&
                    replacementText.isNotEmpty() &&
                    boundaries.beforeEnd - boundaries.start <= maxRuleCodePoints &&
                    boundaries.afterEnd - boundaries.start <= maxRuleCodePoints

            return SingleReplacementDiff(
                startCodePoint = boundaries.start,
                endCodePointExclusive = boundaries.beforeEnd,
                observedText = observedText,
                replacementText = replacementText,
                eligibleForRule = isShortSubstitution
            )
        }

        private fun commonPrefixLength(first: IntArray, second: IntArray): Int {
            val limit = minOf(first.size, second.size)
            var length = 0
            while (length < limit && first[length] == second[length]) length++
            return length
        }

        private fun commonSuffixLength(first: IntArray, second: IntArray, prefixLength: Int): Int {
            val limit = minOf(first.size, second.size) - prefixLength
            var length = 0
            while (
                length < limit &&
                first[first.lastIndex - length] == second[second.lastIndex - length]
            ) {
                length++
            }
            return length
        }

        private fun hasCommonCodePoint(first: IntArray, second: IntArray): Boolean {
            if (first.isEmpty() || second.isEmpty()) return false
            val smaller = if (first.size <= second.size) first else second
            val larger = if (first.size <= second.size) second else first
            val values = smaller.toHashSet()
            return larger.any(values::contains)
        }

        private fun expandSubstitutionToToken(
            before: IntArray,
            after: IntArray,
            start: Int,
            beforeEnd: Int,
            afterEnd: Int
        ): Boundaries {
            if (start == beforeEnd || start == afterEnd) {
                return Boundaries(start, beforeEnd, afterEnd)
            }

            var expandedStart = start
            var expandedBeforeEnd = beforeEnd
            var expandedAfterEnd = afterEnd
            while (
                expandedStart > 0 &&
                isTokenCodePoint(before[expandedStart - 1])
            ) {
                expandedStart--
            }
            while (
                expandedBeforeEnd < before.size &&
                expandedAfterEnd < after.size &&
                before[expandedBeforeEnd] == after[expandedAfterEnd] &&
                isTokenCodePoint(before[expandedBeforeEnd])
            ) {
                expandedBeforeEnd++
                expandedAfterEnd++
            }
            return Boundaries(expandedStart, expandedBeforeEnd, expandedAfterEnd)
        }

        private fun isTokenCodePoint(codePoint: Int): Boolean =
            Character.isDigit(codePoint) ||
                codePoint == '_'.code ||
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN

        private data class Boundaries(
            val start: Int,
            val beforeEnd: Int,
            val afterEnd: Int
        )
    }
}
