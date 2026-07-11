package app.murmurnote.android.domain.correction

data class PersonalCorrectionCandidate(
    val id: String,
    val ruleId: String,
    val segmentId: Long,
    val startCodePoint: Int,
    val endCodePointExclusive: Int,
    val observedText: String,
    val replacementText: String,
    val leftContext: String,
    val rightContext: String,
) {
    init {
        require(id.isNotBlank()) { "Candidate id must not be blank" }
        require(ruleId.isNotBlank()) { "Candidate rule id must not be blank" }
        require(startCodePoint >= 0) { "Candidate start must not be negative" }
        require(endCodePointExclusive > startCodePoint) { "Candidate range must not be empty" }
        require(observedText.isNotEmpty()) { "Candidate observed text must not be empty" }
        require(replacementText.isNotEmpty()) { "Candidate replacement must not be empty" }
    }
}

data class UntrustedPersonalCorrectionDecision(
    val candidateId: String,
    val action: String,
    val confidence: String,
    val reasonCode: String,
)

object PersonalCorrectionPlanValidator {
    const val MAX_CANDIDATES_PER_RECORDING: Int = 24

    private val allowedApplyReasons = setOf(
        "PHONETIC_ASR_ERROR",
        "USER_TERM_FITS_CONTEXT",
        "PROPER_NOUN_FITS_CONTEXT",
    )

    fun approve(
        candidates: List<PersonalCorrectionCandidate>,
        decisions: List<UntrustedPersonalCorrectionDecision>,
    ): List<PersonalCorrectionCandidate> {
        val boundedCandidates = candidates.take(MAX_CANDIDATES_PER_RECORDING)
        val candidatesById = boundedCandidates.associateBy { it.id }
        val uniqueDecisions = decisions
            .groupBy { it.candidateId }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }

        val provisionallyApproved = uniqueDecisions.mapNotNull { (id, decision) ->
            val candidate = candidatesById[id] ?: return@mapNotNull null
            candidate.takeIf {
                decision.action == "APPLY" &&
                    decision.confidence == "HIGH" &&
                    decision.reasonCode in allowedApplyReasons
            }
        }
        val overlapping = provisionallyApproved.filter { candidate ->
            provisionallyApproved.any { other ->
                candidate.id != other.id && candidate.overlaps(other)
            }
        }.mapTo(mutableSetOf()) { it.id }

        return provisionallyApproved
            .filterNot { it.id in overlapping }
            .sortedWith(compareBy({ it.segmentId }, { it.startCodePoint }, { it.id }))
    }

    private fun PersonalCorrectionCandidate.overlaps(other: PersonalCorrectionCandidate): Boolean =
        segmentId == other.segmentId &&
            startCodePoint < other.endCodePointExclusive &&
            other.startCodePoint < endCodePointExclusive
}
