package app.murmurnote.android.data.repository

import androidx.room.withTransaction
import app.murmurnote.android.data.asr.AsrProvenance
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.CorrectionDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
import app.murmurnote.android.data.local.entity.CorrectionRecordEntity
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import app.murmurnote.android.data.local.entity.RawTranscriptProvenance
import app.murmurnote.android.data.local.entity.TranscriptRevisionEntity
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.domain.correction.CorrectionDecision
import app.murmurnote.android.domain.correction.CorrectionMatchMode
import app.murmurnote.android.domain.correction.CorrectionRule
import app.murmurnote.android.domain.correction.CorrectionScope
import app.murmurnote.android.domain.correction.DeterministicCorrectionEngine
import app.murmurnote.android.domain.correction.SingleReplacementDiff
import app.murmurnote.android.domain.transcript.CorrectionAuditReason
import app.murmurnote.android.domain.transcript.CorrectionAuditRecord
import app.murmurnote.android.domain.transcript.ModelTranscriptSegment
import app.murmurnote.android.domain.transcript.ModelTranscriptBoundary
import app.murmurnote.android.domain.transcript.TranscriptRevision
import app.murmurnote.android.domain.transcript.TranscriptRevisionSource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class TranscriptRepository @Inject constructor(
    private val database: MurmurnoteDatabase,
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
    private val correctionDao: CorrectionDao
) {
    private val correctionEngine = DeterministicCorrectionEngine()

    /**
     * Returns only provisional model segments that exactly match the frozen attempt and planned
     * neural-VAD boundaries. An incompatible incomplete cache is discarded as a whole; finalized
     * or legacy raw data is never touched by this API.
     */
    suspend fun prepareModelAttempt(
        recordingId: String,
        provenance: AsrProvenance,
        plannedSegments: List<ModelTranscriptBoundary>,
        allowProvisionalReuse: Boolean = true
    ): List<TranscriptSegment> = database.withTransaction {
        requireValidProvenance(provenance)
        require(plannedSegments.map { it.sequence } == plannedSegments.indices.toList()) {
            "Planned segment sequences must be continuous and start at zero"
        }
        val recording = requireRecording(recordingId)
        check(recording.rawTranscript == null) {
            "Recording $recordingId already has immutable raw transcript data"
        }
        check(recording.correctionRevision == INITIAL_REVISION) {
            "Recording $recordingId has correction history"
        }
        check(transcriptDao.getRevisions(recordingId).isEmpty()) {
            "Recording $recordingId already has transcript revision history"
        }

        val plannedBySequence = plannedSegments.associateBy { it.sequence }
        val existing = transcriptDao.getSegments(recordingId)
        val compatible = allowProvisionalReuse && existing.all { segment ->
            val planned = plannedBySequence[segment.sequence]
            planned != null &&
                segment.startMs == planned.startMs &&
                segment.endMs == planned.endMs &&
                segment.cutReason == planned.cutReason?.name &&
                segment.overlapBeforeMs == planned.overlapBeforeMs &&
                segment.rawProvenance == RawTranscriptProvenance.MODEL_OUTPUT &&
                segment.asrConfigFingerprint == provenance.configFingerprint &&
                segment.vadPresetVersion == provenance.vadPresetVersion &&
                segment.correctionRevision == INITIAL_REVISION &&
                !segment.isEdited
        }
        if (compatible) {
            existing
        } else {
            transcriptDao.deleteProvisionalSegments(recordingId)
            emptyList()
        }
    }

    suspend fun cacheModelSegment(
        recordingId: String,
        segment: ModelTranscriptSegment,
        provenance: AsrProvenance
    ): TranscriptSegment = database.withTransaction {
        requireValidProvenance(provenance)
        val recording = requireRecording(recordingId)
        check(recording.rawTranscript == null && recording.correctionRevision == INITIAL_REVISION) {
            "Recording $recordingId already has finalized transcript data"
        }
        check(transcriptDao.getRevisions(recordingId).isEmpty()) {
            "Recording $recordingId already has transcript revision history"
        }

        val candidate = TranscriptSegment(
            recordingId = recordingId,
            rawText = segment.rawText,
            correctedText = segment.rawText,
            startMs = segment.startMs,
            endMs = segment.endMs,
            sequence = segment.sequence,
            rawProvenance = RawTranscriptProvenance.MODEL_OUTPUT,
            asrConfigFingerprint = provenance.configFingerprint,
            vadPresetVersion = provenance.vadPresetVersion,
            cutReason = segment.cutReason?.name,
            overlapBeforeMs = segment.overlapBeforeMs
        )
        val insertedId = transcriptDao.insertModelSegment(candidate)
        if (insertedId != INSERT_IGNORED) {
            return@withTransaction candidate.copy(id = insertedId)
        }

        val existing = transcriptDao.getSegmentBySequence(recordingId, segment.sequence)
            ?: throw TranscriptPersistenceException(
                "Segment insert was ignored but no existing row was found for " +
                    "$recordingId sequence ${segment.sequence}"
            )
        if (existing.isSameModelAttemptAs(candidate)) {
            existing
        } else {
            throw TranscriptSegmentConflictException(recordingId, segment.sequence)
        }
    }

    suspend fun finalizeModelTranscript(
        recordingId: String,
        provenance: AsrProvenance,
        expectedSequences: List<Int>,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        requireValidProvenance(provenance)
        require(expectedSequences == expectedSequences.indices.toList()) {
            "Expected segment sequences must be continuous and start at zero"
        }

        val recording = requireRecording(recordingId)
        check(recording.correctionRevision == INITIAL_REVISION) {
            "Recording $recordingId has manual revision ${recording.correctionRevision}"
        }
        check(
            recording.rawTranscript == null &&
                recording.rawProvenance != RawTranscriptProvenance.LEGACY_PROVENANCE_UNKNOWN
        ) {
            "Recording $recordingId already has immutable or legacy raw transcript data"
        }
        check(transcriptDao.getRevisions(recordingId).isEmpty()) {
            "Recording $recordingId already has transcript revision history"
        }

        val segments = transcriptDao.getSegments(recordingId)
        check(segments.map { it.sequence } == expectedSequences) {
            "Recording $recordingId does not contain the complete expected segment sequence"
        }
        segments.forEach { segment ->
            check(segment.rawProvenance == RawTranscriptProvenance.MODEL_OUTPUT) {
                "Segment ${segment.sequence} has non-model raw provenance"
            }
            check(segment.asrConfigFingerprint == provenance.configFingerprint) {
                "Segment ${segment.sequence} belongs to a different ASR configuration"
            }
            check(segment.vadPresetVersion == provenance.vadPresetVersion) {
                "Segment ${segment.sequence} belongs to a different VAD preset"
            }
            check(segment.correctionRevision == INITIAL_REVISION && !segment.isEdited) {
                "Segment ${segment.sequence} was already edited or corrected at a later revision"
            }
        }

        val rules = correctionDao.getApplicableRuleCandidates(recordingId).map { it.toDomainRule() }
        val results = segments.map { segment ->
            correctionEngine.correct(segment.rawText, recordingId, rules)
        }
        segments.zip(results).forEach { (segment, result) ->
            val updated = transcriptDao.setInitialCorrection(
                recordingId = recordingId,
                segmentId = segment.id,
                correctedText = result.correctedText
            )
            check(updated == 1) {
                "Segment ${segment.sequence} changed while finalizing recording $recordingId"
            }
        }

        val rawTranscript = segments.joinToString(SEGMENT_SEPARATOR) { it.rawText }
        val correctedTranscript = results.joinToString(SEGMENT_SEPARATOR) { it.correctedText }
        val recordingUpdated = recordingDao.finalizeModelTranscript(
            id = recordingId,
            rawTranscript = rawTranscript,
            correctedTranscript = correctedTranscript,
            rawProvenance = RawTranscriptProvenance.MODEL_OUTPUT,
            asrEngineType = provenance.engineType.name,
            asrModelId = provenance.modelId,
            asrConfigFingerprint = provenance.configFingerprint,
            asrConfigSnapshotJson = provenance.configSnapshotJson,
            vadPresetVersion = provenance.vadPresetVersion
        )
        check(recordingUpdated == 1) {
            "Recording $recordingId changed while finalizing its immutable transcript"
        }
        transcriptDao.insertRevision(
            TranscriptRevisionEntity(
                recordingId = recordingId,
                revision = INITIAL_REVISION,
                text = correctedTranscript,
                source = TranscriptRevisionSource.MODEL_FINAL.name,
                createdAt = now
            )
        )

        val records = results.toPersistedCorrectionRecords(recordingId, now)
        if (records.isNotEmpty()) correctionDao.insertRecords(records)
    }

    suspend fun editSegment(
        recordingId: String,
        segmentId: Long,
        newText: String,
        now: Long = System.currentTimeMillis()
    ): SingleReplacementDiff? = database.withTransaction {
        val recording = requireFinalizedRecording(recordingId)
        check(recording.correctionRevision < Long.MAX_VALUE) {
            "Recording $recordingId exhausted its correction revision range"
        }
        val segment = transcriptDao.getSegment(recordingId, segmentId)
            ?: throw TranscriptPersistenceException(
                "Transcript segment $segmentId does not belong to recording $recordingId"
            )
        if (newText == segment.correctedText) return@withTransaction null
        val newRevision = recording.correctionRevision + 1
        val candidate = SingleReplacementDiff.between(segment.correctedText, newText)

        val updated = transcriptDao.setManualCorrection(
            recordingId = recordingId,
            segmentId = segmentId,
            expectedSegmentRevision = segment.correctionRevision,
            correctedText = newText,
            editedAt = now,
            newRevision = newRevision
        )
        check(updated == 1) { "Transcript segment $segmentId changed during manual edit" }

        val updatedSegments = transcriptDao.getSegments(recordingId)
        val correctedTranscript = updatedSegments.joinToString(SEGMENT_SEPARATOR) { it.correctedText }
        val recordingUpdated = recordingDao.updateCorrectedTranscriptRevision(
            id = recordingId,
            expectedRevision = recording.correctionRevision,
            newRevision = newRevision,
            correctedTranscript = correctedTranscript,
            transcriptDirty = recording.hasSummarySnapshot(),
            editedAt = now
        )
        check(recordingUpdated == 1) {
            "Recording $recordingId changed during manual edit"
        }
        transcriptDao.insertRevision(
            TranscriptRevisionEntity(
                recordingId = recordingId,
                revision = newRevision,
                text = correctedTranscript,
                source = TranscriptRevisionSource.MANUAL_EDIT.name,
                createdAt = now
            )
        )
        val rawOffset = rawOffsetOf(updatedSegments, segmentId)
        correctionDao.insertRecords(
            listOf(
                CorrectionRecordEntity(
                    recordingId = recordingId,
                    revision = newRevision,
                    rawStartCodePoint = rawOffset,
                    rawEndCodePointExclusive = rawOffset + segment.rawText.codePointLength(),
                    originalText = segment.correctedText,
                    replacementText = newText,
                    decision = CorrectionDecision.APPLIED.name,
                    reason = CorrectionAuditReason.MANUAL_EDIT.name,
                    createdAt = now
                )
            )
        )
        candidate
    }

    suspend fun rememberRule(
        recordingId: String,
        diff: SingleReplacementDiff,
        scope: CorrectionScope = CorrectionScope.RECORDING,
        now: Long = System.currentTimeMillis()
    ): CorrectionRule = database.withTransaction {
        require(diff.eligibleForRule) { "This edit is not eligible for an exact correction rule" }
        require(diff.observedText.isNotEmpty()) { "Rule observed text must not be empty" }
        require(diff.replacementText.isNotEmpty()) { "Rule replacement text must not be empty" }
        requireRecording(recordingId)

        val existingRules = correctionDao.getAllEnabledRules()
        val relevantRules = existingRules.filter { existing ->
            scope == CorrectionScope.GLOBAL ||
                existing.scopeRecordingId == null ||
                existing.scopeRecordingId == recordingId
        }
        val exactDuplicate = relevantRules.firstOrNull { existing ->
            existing.observedText == diff.observedText &&
                existing.replacementText == diff.replacementText
        }
        if (exactDuplicate != null) {
            val duplicateAlreadyCoversScope = exactDuplicate.scopeRecordingId == null ||
                (scope == CorrectionScope.RECORDING &&
                    exactDuplicate.scopeRecordingId == recordingId)
            if (duplicateAlreadyCoversScope) return@withTransaction exactDuplicate.toDomainRule()
            throw CorrectionRuleConflictException(
                "An equivalent recording rule must be resolved before global promotion"
            )
        }
        val conflict = relevantRules.firstOrNull { existing ->
            existing.observedText == diff.observedText ||
                (existing.observedText == diff.replacementText &&
                    existing.replacementText == diff.observedText)
        }
        if (conflict != null) {
            throw CorrectionRuleConflictException(
                "The exact replacement conflicts with an enabled correction rule"
            )
        }

        val entity = CorrectionRuleEntity(
            id = UUID.randomUUID().toString(),
            observedText = diff.observedText,
            replacementText = diff.replacementText,
            scope = scope.name,
            scopeRecordingId = when (scope) {
                CorrectionScope.RECORDING -> recordingId
                CorrectionScope.GLOBAL -> null
            },
            matchMode = CorrectionMatchMode.EXACT_TEXT.name,
            createdAt = now,
            updatedAt = now
        )
        correctionDao.insertRule(entity)
        entity.toDomainRule()
    }

    suspend fun revertToRaw(
        recordingId: String,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        val recording = requireFinalizedRecording(recordingId)
        check(recording.correctionRevision < Long.MAX_VALUE) {
            "Recording $recordingId exhausted its correction revision range"
        }
        val segments = transcriptDao.getSegments(recordingId)
        check(segments.isNotEmpty()) { "Recording $recordingId has no transcript segments" }
        val rawTranscript = segments.joinToString(SEGMENT_SEPARATOR) { it.rawText }
        check(recording.rawTranscript == rawTranscript) {
            "Recording $recordingId raw transcript does not match its immutable segments"
        }
        val newRevision = recording.correctionRevision + 1
        val updatedCount = transcriptDao.revertCorrectionsToRaw(recordingId, newRevision)
        check(updatedCount == segments.size) {
            "Transcript segments changed while reverting recording $recordingId"
        }

        val recordingUpdated = recordingDao.updateCorrectedTranscriptRevision(
            id = recordingId,
            expectedRevision = recording.correctionRevision,
            newRevision = newRevision,
            correctedTranscript = rawTranscript,
            transcriptDirty = recording.hasSummarySnapshot(),
            editedAt = now
        )
        check(recordingUpdated == 1) {
            "Recording $recordingId changed while reverting to raw"
        }
        transcriptDao.insertRevision(
            TranscriptRevisionEntity(
                recordingId = recordingId,
                revision = newRevision,
                text = rawTranscript,
                source = TranscriptRevisionSource.REVERT_TO_RAW.name,
                createdAt = now
            )
        )
        correctionDao.insertRecords(
            segments.mapWithRawOffsets { segment, rawOffset ->
                CorrectionRecordEntity(
                    recordingId = recordingId,
                    revision = newRevision,
                    rawStartCodePoint = rawOffset,
                    rawEndCodePointExclusive = rawOffset + segment.rawText.codePointLength(),
                    originalText = segment.correctedText,
                    replacementText = segment.rawText,
                    decision = CorrectionDecision.APPLIED.name,
                    reason = CorrectionAuditReason.REVERT_TO_RAW.name,
                    createdAt = now
                )
            }
        )
    }

    suspend fun setRuleEnabled(
        recordingId: String,
        ruleId: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        requireRecording(recordingId)
        val rule = correctionDao.getRule(ruleId)
            ?: throw TranscriptPersistenceException("Correction rule does not exist")
        check(rule.scopeRecordingId == null || rule.scopeRecordingId == recordingId) {
            "Correction rule does not belong to this recording"
        }
        check(correctionDao.setRuleEnabled(ruleId, enabled, now) == 1) {
            "Correction rule changed while updating its state"
        }
    }

    suspend fun getSegments(recordingId: String): List<TranscriptSegment> =
        transcriptDao.getSegments(recordingId)

    fun observeSegments(recordingId: String): Flow<List<TranscriptSegment>> =
        transcriptDao.observeSegments(recordingId)

    suspend fun getRevisions(recordingId: String): List<TranscriptRevision> =
        transcriptDao.getRevisions(recordingId).map { it.toDomainRevision() }

    fun observeRevisions(recordingId: String): Flow<List<TranscriptRevision>> =
        transcriptDao.observeRevisions(recordingId).map { revisions ->
            revisions.map { it.toDomainRevision() }
        }

    suspend fun getRules(recordingId: String): List<CorrectionRule> =
        correctionDao.getRules(recordingId).map { it.toDomainRule() }

    fun observeRules(recordingId: String): Flow<List<CorrectionRule>> =
        correctionDao.observeRules(recordingId).map { rules -> rules.map { it.toDomainRule() } }

    suspend fun getAuditRecords(recordingId: String): List<CorrectionAuditRecord> =
        correctionDao.getRecords(recordingId).map { it.toDomainAuditRecord() }

    fun observeAuditRecords(recordingId: String): Flow<List<CorrectionAuditRecord>> =
        correctionDao.observeRecords(recordingId).map { records ->
            records.map { it.toDomainAuditRecord() }
        }

    private suspend fun requireRecording(recordingId: String) =
        recordingDao.getById(recordingId)
            ?: throw TranscriptPersistenceException("Recording $recordingId does not exist")

    private suspend fun requireFinalizedRecording(recordingId: String) =
        requireRecording(recordingId).also { recording ->
            check(recording.rawTranscript != null) {
                "Recording $recordingId has not finalized its model transcript"
            }
        }

    private fun app.murmurnote.android.data.local.entity.Recording.hasSummarySnapshot(): Boolean =
        summary != null || draftSummary != null || finalSummary != null

    private fun requireValidProvenance(provenance: AsrProvenance) {
        require(provenance.modelId.isNotBlank()) { "ASR model id must not be blank" }
        require(provenance.configFingerprint.isNotBlank()) { "ASR fingerprint must not be blank" }
        require(provenance.configSnapshotJson.isNotBlank()) { "ASR snapshot must not be blank" }
        require(provenance.vadPresetVersion.isNotBlank()) { "VAD preset version must not be blank" }
    }

    private fun List<app.murmurnote.android.domain.correction.CorrectionResult>
        .toPersistedCorrectionRecords(
            recordingId: String,
            createdAt: Long
        ): List<CorrectionRecordEntity> {
        var rawOffset = 0
        return flatMapIndexed { index, result ->
            val records = result.records.map { record ->
                CorrectionRecordEntity(
                    recordingId = recordingId,
                    revision = INITIAL_REVISION,
                    sourceRuleId = record.sourceRuleId,
                    rawStartCodePoint = rawOffset + record.rawStartCodePoint,
                    rawEndCodePointExclusive = rawOffset + record.rawEndCodePointExclusive,
                    originalText = record.originalText,
                    replacementText = record.replacementText,
                    decision = record.decision.name,
                    reason = record.decisionReason.name,
                    createdAt = createdAt
                )
            }
            rawOffset += result.rawText.codePointLength()
            if (index < lastIndex) rawOffset += SEGMENT_SEPARATOR_CODE_POINTS
            records
        }
    }

    private fun rawOffsetOf(segments: List<TranscriptSegment>, segmentId: Long): Int {
        var rawOffset = 0
        segments.forEachIndexed { index, segment ->
            if (segment.id == segmentId) return rawOffset
            rawOffset += segment.rawText.codePointLength()
            if (index < segments.lastIndex) rawOffset += SEGMENT_SEPARATOR_CODE_POINTS
        }
        throw TranscriptPersistenceException("Transcript segment $segmentId disappeared during edit")
    }

    private inline fun <T> List<TranscriptSegment>.mapWithRawOffsets(
        transform: (TranscriptSegment, Int) -> T
    ): List<T> {
        var rawOffset = 0
        return mapIndexed { index, segment ->
            transform(segment, rawOffset).also {
                rawOffset += segment.rawText.codePointLength()
                if (index < lastIndex) rawOffset += SEGMENT_SEPARATOR_CODE_POINTS
            }
        }
    }

    private fun TranscriptSegment.isSameModelAttemptAs(other: TranscriptSegment): Boolean =
        recordingId == other.recordingId &&
            rawText == other.rawText &&
            startMs == other.startMs &&
            endMs == other.endMs &&
            sequence == other.sequence &&
            rawProvenance == other.rawProvenance &&
            asrConfigFingerprint == other.asrConfigFingerprint &&
            vadPresetVersion == other.vadPresetVersion &&
            cutReason == other.cutReason &&
            overlapBeforeMs == other.overlapBeforeMs

    private companion object {
        const val INSERT_IGNORED = -1L
        const val INITIAL_REVISION = 0L
        const val SEGMENT_SEPARATOR = "\n"
        const val SEGMENT_SEPARATOR_CODE_POINTS = 1
    }
}

open class TranscriptPersistenceException(message: String) : IllegalStateException(message)

class TranscriptSegmentConflictException(recordingId: String, sequence: Int) :
    TranscriptPersistenceException(
        "Immutable transcript conflict for recording $recordingId sequence $sequence"
    )

class CorrectionRuleConflictException(message: String) :
    TranscriptPersistenceException(message)

private fun CorrectionRuleEntity.toDomainRule(): CorrectionRule = CorrectionRule(
    id = id,
    observedText = observedText,
    replacementText = replacementText,
    matchMode = strictEnumValueOf(matchMode, "correction rule match mode"),
    scope = strictEnumValueOf(scope, "correction rule scope"),
    scopeId = scopeRecordingId,
    isEnabled = isEnabled
)

private fun TranscriptRevisionEntity.toDomainRevision(): TranscriptRevision = TranscriptRevision(
    id = id,
    recordingId = recordingId,
    revision = revision,
    text = text,
    source = strictEnumValueOf(source, "transcript revision source"),
    createdAt = createdAt
)

private fun CorrectionRecordEntity.toDomainAuditRecord(): CorrectionAuditRecord =
    CorrectionAuditRecord(
        id = id,
        recordingId = recordingId,
        revision = revision,
        sourceRuleId = sourceRuleId,
        rawStartCodePoint = rawStartCodePoint,
        rawEndCodePointExclusive = rawEndCodePointExclusive,
        originalText = originalText,
        replacementText = replacementText,
        decision = strictEnumValueOf(decision, "correction audit decision"),
        reason = strictEnumValueOf(reason, "correction audit reason"),
        createdAt = createdAt
    )

private inline fun <reified T : Enum<T>> strictEnumValueOf(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Unknown persisted $field: $value")

private fun String.codePointLength(): Int = codePointCount(0, length)
