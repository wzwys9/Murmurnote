package app.murmurnote.android.domain.transcript

import app.murmurnote.android.domain.correction.CorrectionDecision

data class ModelTranscriptSegment(
    val rawText: String,
    val startMs: Long,
    val endMs: Long,
    val sequence: Int,
    val cutReason: ModelSegmentCutReason? = null,
    val overlapBeforeMs: Long = 0
) {
    init {
        require(startMs >= 0) { "Segment start must not be negative" }
        require(endMs > startMs) { "Segment end must be after its start" }
        require(sequence >= 0) { "Segment sequence must not be negative" }
        require(overlapBeforeMs >= 0) { "Segment overlap must not be negative" }
    }
}

data class ModelTranscriptBoundary(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val cutReason: ModelSegmentCutReason? = null,
    val overlapBeforeMs: Long = 0
) {
    init {
        require(sequence >= 0) { "Segment sequence must not be negative" }
        require(startMs >= 0) { "Segment start must not be negative" }
        require(endMs > startMs) { "Segment end must be after its start" }
        require(overlapBeforeMs >= 0) { "Segment overlap must not be negative" }
    }
}

enum class ModelSegmentCutReason {
    NATURAL_PAUSE,
    REFINED_HARD_LIMIT,
    FALLBACK_HARD_LIMIT,
    END_OF_AUDIO
}

enum class TranscriptRevisionSource {
    MODEL_FINAL,
    MANUAL_EDIT,
    REVERT_TO_RAW,
    LEGACY_MIGRATION
}

data class TranscriptRevision(
    val id: Long,
    val recordingId: String,
    val revision: Long,
    val text: String,
    val source: TranscriptRevisionSource,
    val createdAt: Long
)

enum class CorrectionAuditReason {
    EXACT_TEXT_RULE_APPLIED,
    CONFLICTING_RULES,
    OVERLAPS_HIGHER_PRIORITY,
    MANUAL_EDIT,
    REVERT_TO_RAW
}

data class CorrectionAuditRecord(
    val id: Long,
    val recordingId: String,
    val revision: Long,
    val sourceRuleId: String?,
    val rawStartCodePoint: Int,
    val rawEndCodePointExclusive: Int,
    val originalText: String,
    val replacementText: String,
    val decision: CorrectionDecision,
    val reason: CorrectionAuditReason,
    val createdAt: Long
)
