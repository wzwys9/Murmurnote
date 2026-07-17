package app.murmurnote.android.domain.correction

data class PersonalCorrectionContextHint(
    val leftContext: String,
    val rightContext: String,
    val pinyinRelation: PinyinRelation = PinyinRelation.UNAVAILABLE,
)

object PersonalCorrectionCandidateFinder {
    const val MAX_CANDIDATES_PER_SEGMENT: Int = 6
    const val MAX_CONTEXT_SIDE_CODE_POINTS: Int = 80

    fun find(
        segmentId: Long,
        text: String,
        rules: List<CorrectionRule>,
        contextHints: Map<String, PersonalCorrectionContextHint> = emptyMap(),
    ): List<PersonalCorrectionCandidate> {
        val textCodePoints = CodePointText.values(text)
        if (textCodePoints.size > MAX_TEXT_CODE_POINTS) return emptyList()
        val eligibleRules = rules
            .asSequence()
            .filter {
                it.isEnabled &&
                    it.scope == CorrectionScope.GLOBAL &&
                    it.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM &&
                    it.id.length <= MAX_RULE_ID_CHARS &&
                    it.observedText != it.replacementText &&
                    it.observedText.correctionCodePointLength() in
                        1..PersonalCorrectionLearningPolicy.MAX_TERM_CODE_POINTS &&
                    it.replacementText.correctionCodePointLength() in
                        1..PersonalCorrectionLearningPolicy.MAX_TERM_CODE_POINTS &&
                    !it.observedText.containsUnsafeCorrectionCodePoint() &&
                    !it.replacementText.containsUnsafeCorrectionCodePoint()
            }
            .map { it to CodePointText.values(it.observedText) }
            .filter { (_, observed) -> observed.isNotEmpty() && observed.size <= textCodePoints.size }
            .sortedWith(compareBy({ -it.second.size }, { it.first.id }))
            .toList()

        val matches = buildList {
            for (start in textCodePoints.indices) {
                for ((rule, observed) in eligibleRules) {
                    if (!textCodePoints.matchesAt(start, observed)) continue
                    val end = start + observed.size
                    val leftStart = (start - MAX_CONTEXT_SIDE_CODE_POINTS).coerceAtLeast(0)
                    val rightEnd = (end + MAX_CONTEXT_SIDE_CODE_POINTS)
                        .coerceAtMost(textCodePoints.size)
                    val candidate = PersonalCorrectionCandidate(
                        id = "s${segmentId}p${start}r${rule.id}",
                        ruleId = rule.id,
                        ruleOrigin = rule.origin,
                        segmentId = segmentId,
                        startCodePoint = start,
                        endCodePointExclusive = end,
                        observedText = rule.observedText,
                        replacementText = rule.replacementText,
                        leftContext = CodePointText.slice(text, leftStart, start),
                        rightContext = CodePointText.slice(text, end, rightEnd),
                    )
                    add(
                        ScoredCandidate(
                            candidate = candidate,
                            contextScore = contextHints[rule.id]
                                ?.score(candidate)
                                ?: 0,
                        ),
                    )
                }
            }
        }
        return matches
            .sortedWith(
                compareByDescending<ScoredCandidate> {
                    it.candidate.ruleOrigin == CorrectionRuleOrigin.USER_DEFINED
                }
                    .thenByDescending { it.contextScore }
                    .thenBy { it.candidate.startCodePoint }
                    .thenBy { it.candidate.ruleId },
            )
            .take(MAX_CANDIDATES_PER_SEGMENT)
            .map { it.candidate }
    }

    private fun IntArray.matchesAt(start: Int, expected: IntArray): Boolean {
        if (start + expected.size > size) return false
        for (index in expected.indices) {
            if (this[start + index] != expected[index]) return false
        }
        return true
    }

    private fun PersonalCorrectionContextHint.score(
        candidate: PersonalCorrectionCandidate,
    ): Int {
        val contextScore = commonSuffix(
            CodePointText.values(leftContext),
            CodePointText.values(candidate.leftContext),
        ) + commonPrefix(
            CodePointText.values(rightContext),
            CodePointText.values(candidate.rightContext),
        )
        val pinyinScore = when (pinyinRelation) {
            PinyinRelation.EXACT_PINYIN -> 2
            PinyinRelation.NEAR_PINYIN -> 1
            PinyinRelation.NOT_PHONETIC,
            PinyinRelation.UNAVAILABLE -> 0
        }
        return contextScore * CONTEXT_SCORE_WEIGHT + pinyinScore
    }

    private fun commonPrefix(first: IntArray, second: IntArray): Int {
        val limit = minOf(first.size, second.size, MAX_CONTEXT_SCORE_CODE_POINTS)
        var count = 0
        while (count < limit && first[count] == second[count]) count++
        return count
    }

    private fun commonSuffix(first: IntArray, second: IntArray): Int {
        val limit = minOf(first.size, second.size, MAX_CONTEXT_SCORE_CODE_POINTS)
        var count = 0
        while (
            count < limit &&
            first[first.lastIndex - count] == second[second.lastIndex - count]
        ) {
            count++
        }
        return count
    }

    private data class ScoredCandidate(
        val candidate: PersonalCorrectionCandidate,
        val contextScore: Int,
    )

    private const val MAX_RULE_ID_CHARS = 80
    private const val MAX_TEXT_CODE_POINTS = 10_000
    private const val MAX_CONTEXT_SCORE_CODE_POINTS = 16
    private const val CONTEXT_SCORE_WEIGHT = 4
}
