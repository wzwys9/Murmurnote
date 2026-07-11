package app.murmurnote.android.domain.correction

class DeterministicCorrectionEngine {

    fun correct(
        rawText: String,
        recordingId: String?,
        rules: List<CorrectionRule>
    ): CorrectionResult {
        val rawCodePoints = CodePointText.values(rawText)
        val candidates = rules
            .asSequence()
            .filter { it.isEnabled && it.appliesTo(recordingId) }
            .flatMap { rule -> findCandidates(rawCodePoints, rule).asSequence() }
            .toList()

        if (candidates.isEmpty()) {
            return CorrectionResult(rawText = rawText, correctedText = rawText, records = emptyList())
        }

        val conflicting = findConflictingCandidates(candidates)
        val rejectedReasons = mutableMapOf<Candidate, CorrectionDecisionReason>()
        val selected = mutableListOf<Candidate>()

        candidates
            .sortedWith(candidatePriority)
            .forEach { candidate ->
                when {
                    conflicting.any(candidate::overlaps) -> {
                        rejectedReasons[candidate] = CorrectionDecisionReason.CONFLICTING_RULES
                    }
                    selected.any(candidate::overlaps) -> {
                        rejectedReasons[candidate] = CorrectionDecisionReason.OVERLAPS_HIGHER_PRIORITY
                    }
                    else -> selected += candidate
                }
            }

        val correctedText = buildCorrectedText(rawText, selected)
        val records = candidates
            .sortedWith(recordOrder)
            .map { candidate ->
                val rejectionReason = rejectedReasons[candidate]
                CorrectionRecord(
                    sourceRuleId = candidate.rule.id,
                    rawStartCodePoint = candidate.startCodePoint,
                    rawEndCodePointExclusive = candidate.endCodePointExclusive,
                    originalText = CodePointText.slice(
                        rawText,
                        candidate.startCodePoint,
                        candidate.endCodePointExclusive
                    ),
                    replacementText = candidate.rule.replacementText,
                    scope = candidate.rule.scope,
                    decision = if (rejectionReason == null) {
                        CorrectionDecision.APPLIED
                    } else {
                        CorrectionDecision.REJECTED
                    },
                    decisionReason = rejectionReason
                        ?: CorrectionDecisionReason.EXACT_TEXT_RULE_APPLIED
                )
            }

        return CorrectionResult(rawText = rawText, correctedText = correctedText, records = records)
    }

    private fun CorrectionRule.appliesTo(recordingId: String?): Boolean = when (scope) {
        CorrectionScope.RECORDING -> scopeId == recordingId
        CorrectionScope.GLOBAL -> true
    }

    private fun findCandidates(rawCodePoints: IntArray, rule: CorrectionRule): List<Candidate> {
        check(rule.matchMode == CorrectionMatchMode.EXACT_TEXT)
        val observedCodePoints = CodePointText.values(rule.observedText)
        if (observedCodePoints.size > rawCodePoints.size) return emptyList()

        return buildList {
            for (start in 0..rawCodePoints.size - observedCodePoints.size) {
                if (rawCodePoints.matchesAt(start, observedCodePoints)) {
                    add(
                        Candidate(
                            rule = rule,
                            startCodePoint = start,
                            endCodePointExclusive = start + observedCodePoints.size
                        )
                    )
                }
            }
        }
    }

    private fun IntArray.matchesAt(start: Int, expected: IntArray): Boolean {
        for (index in expected.indices) {
            if (this[start + index] != expected[index]) return false
        }
        return true
    }

    private fun findConflictingCandidates(candidates: List<Candidate>): Set<Candidate> {
        val conflicting = mutableSetOf<Candidate>()
        for (firstIndex in candidates.indices) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                val first = candidates[firstIndex]
                val second = candidates[secondIndex]
                if (
                    first.overlaps(second) &&
                    first.scopePriority == second.scopePriority &&
                    first.length == second.length
                ) {
                    conflicting += first
                    conflicting += second
                }
            }
        }
        return conflicting
    }

    private fun buildCorrectedText(rawText: String, selected: List<Candidate>): String {
        if (selected.isEmpty()) return rawText

        return buildString {
            var rawCursor = 0
            selected.sortedBy { it.startCodePoint }.forEach { candidate ->
                append(CodePointText.slice(rawText, rawCursor, candidate.startCodePoint))
                append(candidate.rule.replacementText)
                rawCursor = candidate.endCodePointExclusive
            }
            append(
                CodePointText.slice(
                    rawText,
                    rawCursor,
                    rawText.codePointCount(0, rawText.length)
                )
            )
        }
    }

    private data class Candidate(
        val rule: CorrectionRule,
        val startCodePoint: Int,
        val endCodePointExclusive: Int
    ) {
        val length: Int = endCodePointExclusive - startCodePoint
        val scopePriority: Int = when (rule.scope) {
            CorrectionScope.RECORDING -> 0
            CorrectionScope.GLOBAL -> 1
        }

        fun overlaps(other: Candidate): Boolean =
            startCodePoint < other.endCodePointExclusive &&
                other.startCodePoint < endCodePointExclusive
    }

    private companion object {
        val candidatePriority = compareBy<Candidate>(
            { it.scopePriority },
            { -it.length },
            { it.startCodePoint },
            { it.rule.id }
        )

        val recordOrder = compareBy<Candidate>(
            { it.startCodePoint },
            { it.endCodePointExclusive },
            { it.scopePriority },
            { it.rule.id }
        )
    }
}
