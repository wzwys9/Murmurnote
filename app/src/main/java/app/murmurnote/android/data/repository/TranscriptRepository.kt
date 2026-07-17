package app.murmurnote.android.data.repository

import androidx.room.withTransaction
import app.murmurnote.android.data.asr.AsrProvenance
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.CorrectionDao
import app.murmurnote.android.data.local.dao.PersonalCorrectionDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
import app.murmurnote.android.data.local.entity.CorrectionRecordEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningEventEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningProfileEntity
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import app.murmurnote.android.data.local.entity.RawTranscriptProvenance
import app.murmurnote.android.data.local.entity.TranscriptRevisionEntity
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.domain.correction.AppliedTextReplacement
import app.murmurnote.android.domain.correction.CorrectionDecision
import app.murmurnote.android.domain.correction.CorrectionMatchMode
import app.murmurnote.android.domain.correction.CorrectionRule
import app.murmurnote.android.domain.correction.CorrectionRuleOrigin
import app.murmurnote.android.domain.correction.CorrectionScope
import app.murmurnote.android.domain.correction.ContextualCorrectionCapacityExceededException
import app.murmurnote.android.domain.correction.ContextualCorrectionLimits
import app.murmurnote.android.domain.correction.CrossOriginCorrectionConflictPolicy
import app.murmurnote.android.domain.correction.CorrectedTextCoordinateMap
import app.murmurnote.android.domain.correction.DeterministicCorrectionEngine
import app.murmurnote.android.domain.correction.SafeLexiconCreateAction
import app.murmurnote.android.domain.correction.SafeLexiconRulePolicy
import app.murmurnote.android.domain.correction.SingleReplacementDiff
import app.murmurnote.android.domain.correction.PersonalCorrectionEventStatus
import app.murmurnote.android.domain.correction.PersonalCorrectionLearningPolicy
import app.murmurnote.android.domain.correction.PersonalCorrectionLearningState
import app.murmurnote.android.domain.correction.PinyinRelation
import app.murmurnote.android.domain.correction.RawCodePointRange
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
    private val correctionDao: CorrectionDao,
    private val personalCorrectionDao: PersonalCorrectionDao,
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
        applyGlobalLexicon: Boolean = false,
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

        val ruleCandidates = if (applyGlobalLexicon) {
            correctionDao.getApplicableRuleCandidates(recordingId)
        } else {
            correctionDao.getEnabledRecordingRuleCandidates(recordingId)
        }
        val rules = ruleCandidates.mapNotNull { it.toDomainRuleOrNull() }
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
        now: Long = System.currentTimeMillis(),
        capturePersonalLearning: Boolean = false,
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
        val negativeFeedback = if (candidate != null) {
            registerPersonalCorrectionNegativeFeedback(
                recordingId = recordingId,
                segmentId = segmentId,
                priorRevision = recording.correctionRevision,
                diff = candidate,
                now = now,
            )
        } else {
            PersonalCorrectionNegativeFeedback.NONE
        }
        val learningDraft = if (capturePersonalLearning && candidate != null) {
            if (negativeFeedback.matched) {
                negativeFeedback.rawLearningRange?.let { rawRange ->
                    PersonalCorrectionLearningPolicy.fromMappedReplacement(
                        rawText = segment.rawText,
                        rawStartCodePoint = rawRange.startCodePoint,
                        rawEndCodePointExclusive = rawRange.endCodePointExclusive,
                        replacementText = candidate.replacementText,
                    )
                }
            } else {
                PersonalCorrectionLearningPolicy.fromEdit(segment.correctedText, newText)
            }
        } else {
            null
        }

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
        if (learningDraft != null) {
            persistPersonalLearningDraft(
                recordingId = recordingId,
                segmentId = segmentId,
                revision = newRevision,
                draft = learningDraft,
                now = now,
            )
        }
        candidate
    }

    private suspend fun persistPersonalLearningDraft(
        recordingId: String,
        segmentId: Long,
        revision: Long,
        draft: app.murmurnote.android.domain.correction.PersonalCorrectionObservationDraft,
        now: Long,
    ) {
        val proposedRule = CorrectionRule(
            id = "pending-personal-learning",
            observedText = draft.observedText,
            replacementText = draft.replacementText,
            matchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
            origin = CorrectionRuleOrigin.PERSONAL_LEARNING,
            scope = CorrectionScope.GLOBAL,
        )
        val conflictsWithUserDefinition = correctionDao.getEnabledUserDefinedRules()
            .asSequence()
            .mapNotNull { entity -> runCatching { entity.toDomainRule() }.getOrNull() }
            .any { userRule ->
                CrossOriginCorrectionConflictPolicy.conflicts(userRule, proposedRule)
            }
        if (conflictsWithUserDefinition) return

        val existing = personalCorrectionDao.findRule(
            observedText = draft.observedText,
            replacementText = draft.replacementText,
        )
        if (
            existing == null &&
            personalCorrectionDao.countProfiles() >= MAX_PERSONAL_CORRECTION_PROFILES
        ) {
            return
        }
        personalCorrectionDao.getRulesForObservedText(draft.observedText)
            .filter { it.replacementText != draft.replacementText }
            .forEach { conflicting ->
                check(
                    personalCorrectionDao.setProfileState(
                        conflicting.id,
                        PersonalCorrectionLearningState.DISABLED.name,
                        now,
                    ) == 1,
                ) { "Conflicting personal correction profile changed" }
                check(personalCorrectionDao.setRuleEnabled(conflicting.id, false, now) == 1) {
                    "Conflicting personal correction rule changed"
                }
                personalCorrectionDao.supersedePendingEvents(conflicting.id, now)
            }
        val ruleId = if (existing == null) {
            val id = UUID.randomUUID().toString()
            correctionDao.insertRule(
                CorrectionRuleEntity(
                    id = id,
                    observedText = draft.observedText,
                    replacementText = draft.replacementText,
                    scope = CorrectionScope.GLOBAL.name,
                    scopeRecordingId = null,
                    matchMode = CorrectionMatchMode.CONTEXTUAL_LLM.name,
                    origin = CorrectionRuleOrigin.PERSONAL_LEARNING.name,
                    isEnabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            personalCorrectionDao.insertProfile(
                CorrectionLearningProfileEntity(
                    ruleId = id,
                    state = PersonalCorrectionLearningState.PENDING_REVIEW.name,
                    positiveEvidenceCount = 1,
                    negativeEvidenceCount = 0,
                    pinyinRelation = PinyinRelation.UNAVAILABLE.name,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        } else {
            val profile = checkNotNull(personalCorrectionDao.getProfile(existing.id)) {
                "Personal correction rule is missing its learning profile"
            }
            check(personalCorrectionDao.addPositiveEvidence(existing.id, now) == 1) {
                "Personal correction profile changed while adding evidence"
            }
            if (
                profile.state == PersonalCorrectionLearningState.ACTIVE.name ||
                profile.state == PersonalCorrectionLearningState.DISABLED.name
            ) {
                return
            }
            existing.id
        }
        personalCorrectionDao.trimEvents(
            ruleId = ruleId,
            keepNewest = MAX_PERSONAL_CORRECTION_EVENTS_PER_RULE - 1,
        )
        personalCorrectionDao.insertEvent(
            CorrectionLearningEventEntity(
                id = UUID.randomUUID().toString(),
                ruleId = ruleId,
                recordingId = recordingId,
                segmentId = segmentId,
                revision = revision,
                leftContext = draft.leftContext,
                rightContext = draft.rightContext,
                status = PersonalCorrectionEventStatus.PENDING.name,
                pinyinRelation = PinyinRelation.UNAVAILABLE.name,
                createdAt = now,
            ),
        )
    }

    private suspend fun registerPersonalCorrectionNegativeFeedback(
        recordingId: String,
        segmentId: Long,
        priorRevision: Long,
        diff: SingleReplacementDiff,
        now: Long,
    ): PersonalCorrectionNegativeFeedback {
        val segments = transcriptDao.getSegments(recordingId)
        val segment = segments.firstOrNull { it.id == segmentId }
            ?: return PersonalCorrectionNegativeFeedback.NONE
        val segmentRawStart = rawOffsetOf(segments, segmentId)
        val segmentRawEnd = segmentRawStart + segment.rawText.codePointLength()
        val personalizedRecords = correctionDao
            .getLatestAppliedPersonalizedRecordsForSegment(
                recordingId = recordingId,
                maxRevision = priorRevision,
                segmentRawStart = segmentRawStart,
                segmentRawEnd = segmentRawEnd,
            )
        if (personalizedRecords.isEmpty()) return PersonalCorrectionNegativeFeedback.NONE
        val initialRecords = correctionDao
            .getAppliedRecordsForRevision(recordingId, INITIAL_REVISION)
            .filter { record ->
                record.rawStartCodePoint >= segmentRawStart &&
                    record.rawEndCodePointExclusive <= segmentRawEnd
            }
        val coordinateMap = CorrectedTextCoordinateMap.create(
            rawText = segment.rawText,
            correctedText = segment.correctedText,
            replacements = (initialRecords + personalizedRecords)
                .distinctBy { it.id }
                .map { record ->
                    AppliedTextReplacement(
                        rawStartCodePoint = record.rawStartCodePoint - segmentRawStart,
                        rawEndCodePointExclusive =
                            record.rawEndCodePointExclusive - segmentRawStart,
                        originalText = record.originalText,
                        replacementText = record.replacementText,
                    )
                },
        ) ?: return PersonalCorrectionNegativeFeedback.NONE
        val candidateRuleIds = personalizedRecords
            .asSequence()
            .mapNotNull { record ->
                val correctedRange = coordinateMap.correctedRangeForRawReplacement(
                    rawStartCodePoint = record.rawStartCodePoint - segmentRawStart,
                    rawEndCodePointExclusive =
                        record.rawEndCodePointExclusive - segmentRawStart,
                ) ?: return@mapNotNull null
                record.sourceRuleId?.takeIf {
                    if (diff.startCodePoint == diff.endCodePointExclusive) {
                        diff.startCodePoint > correctedRange.startCodePoint &&
                            diff.startCodePoint < correctedRange.endCodePointExclusive
                    } else {
                        diff.startCodePoint < correctedRange.endCodePointExclusive &&
                            correctedRange.startCodePoint < diff.endCodePointExclusive
                    }
                }
            }
            .distinct()
            .toList()
        val matchingRuleIds = candidateRuleIds.filter { ruleId ->
            correctionDao.getRule(ruleId)?.let { rule ->
                rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM.name &&
                    rule.origin == CorrectionRuleOrigin.PERSONAL_LEARNING.name
            } == true
        }
        if (matchingRuleIds.isEmpty()) return PersonalCorrectionNegativeFeedback.NONE
        val rawLearningRange = coordinateMap.rawRangeForCorrectedIncludingReplacements(
            startCodePoint = diff.startCodePoint,
            endCodePointExclusive = diff.endCodePointExclusive,
        )
        matchingRuleIds.forEach { ruleId ->
            check(personalCorrectionDao.registerNegativeFeedback(ruleId, now) == 1) {
                "Personal correction profile changed during negative feedback"
            }
            check(personalCorrectionDao.setRuleEnabled(ruleId, false, now) == 1) {
                "Personal correction rule changed during negative feedback"
            }
            personalCorrectionDao.failPendingEvents(
                ruleId = ruleId,
                reasonCode = "SUPERSEDED_BY_NEGATIVE_FEEDBACK",
                updatedAt = now,
            )
        }
        return PersonalCorrectionNegativeFeedback(
            matched = true,
            rawLearningRange = rawLearningRange,
        )
    }

    suspend fun rememberRecordingRule(
        recordingId: String,
        diff: SingleReplacementDiff,
        now: Long = System.currentTimeMillis()
    ): CorrectionRule = database.withTransaction {
        require(diff.eligibleForRule) { "This edit is not eligible for an exact correction rule" }
        require(diff.observedText.isNotEmpty()) { "Rule observed text must not be empty" }
        require(diff.replacementText.isNotEmpty()) { "Rule replacement text must not be empty" }
        requireRecording(recordingId)

        val recordingRules = correctionDao.getEnabledRecordingRuleCandidates(recordingId)
        val exactDuplicate = recordingRules.firstOrNull { existing ->
            existing.observedText == diff.observedText &&
                existing.replacementText == diff.replacementText
        }
        if (exactDuplicate != null) {
            return@withTransaction exactDuplicate.toDomainRule()
        }
        val conflict = recordingRules.firstOrNull { existing ->
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
            scope = CorrectionScope.RECORDING.name,
            scopeRecordingId = recordingId,
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

    suspend fun setRecordingRuleEnabled(
        recordingId: String,
        ruleId: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        requireRecording(recordingId)
        val rule = correctionDao.getRule(ruleId)
            ?: throw TranscriptPersistenceException("Correction rule does not exist")
        check(
            rule.scope == CorrectionScope.RECORDING.name &&
                rule.scopeRecordingId == recordingId &&
                rule.matchMode == CorrectionMatchMode.EXACT_TEXT.name
        ) {
            "Correction rule does not belong to this recording"
        }
        check(
            correctionDao.setRecordingRuleEnabled(
                recordingId = recordingId,
                id = ruleId,
                enabled = enabled,
                updatedAt = now,
            ) == 1
        ) {
            "Correction rule changed while updating its state"
        }
    }

    fun observeUserDefinedRules(): Flow<List<CorrectionRule>> =
        correctionDao.observeUserDefinedRules().map { rules ->
            rules.mapNotNull { it.toDomainRuleOrNull() }
        }

    suspend fun saveUserDefinedRule(
        observedText: String,
        replacementText: String,
        matchMode: CorrectionMatchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
        now: Long = System.currentTimeMillis(),
    ): CorrectionRule = database.withTransaction {
        val input = SafeLexiconRulePolicy.normalize(observedText, replacementText)
        val existingRules = correctionDao.getUserDefinedRules()
            .mapNotNull { it.toDomainRuleOrNull() }
        val decision = SafeLexiconRulePolicy.decideCreate(input, existingRules)
        val existingDecisionRule = decision.existingRuleId?.let { ruleId ->
            existingRules.firstOrNull { it.id == ruleId }
        }
        if (
            (decision.action == SafeLexiconCreateAction.INSERT &&
                matchMode == CorrectionMatchMode.CONTEXTUAL_LLM) ||
            (decision.action == SafeLexiconCreateAction.REACTIVATE &&
                existingDecisionRule?.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM)
        ) {
            requireUserContextualCapacity(existingRules, existingDecisionRule?.id)
        }
        disableConflictingPersonalLearningRules(
            observedText = input.observedText,
            replacementText = input.replacementText,
            now = now,
        )

        when (decision.action) {
            SafeLexiconCreateAction.INSERT -> {
                val entity = CorrectionRuleEntity(
                    id = UUID.randomUUID().toString(),
                    observedText = input.observedText,
                    replacementText = input.replacementText,
                    scope = CorrectionScope.GLOBAL.name,
                    matchMode = matchMode.name,
                    origin = CorrectionRuleOrigin.USER_DEFINED.name,
                    isEnabled = true,
                    createdAt = now,
                    updatedAt = now,
                )
                correctionDao.insertRule(entity)
                entity.toDomainRule()
            }

            SafeLexiconCreateAction.REUSE_ENABLED -> existingRules.requireDecisionRule(
                decision.existingRuleId,
            )

            SafeLexiconCreateAction.REACTIVATE -> {
                val existing = existingRules.requireDecisionRule(decision.existingRuleId)
                require(
                    correctionDao.setUserDefinedRuleEnabled(
                        id = existing.id,
                        enabled = true,
                        updatedAt = now,
                    ) == 1,
                ) { "词条状态已变化，请重试" }
                existing.copy(isEnabled = true)
            }
        }
    }

    suspend fun setUserDefinedRuleEnabled(
        ruleId: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) = database.withTransaction {
        val rules = correctionDao.getUserDefinedRules()
            .mapNotNull { it.toDomainRuleOrNull() }
        val target = rules.firstOrNull { it.id == ruleId }
        requireNotNull(target) { "找不到这个自定义词条" }

        if (enabled) {
            val hasConflict = rules.any { other ->
                other.id != target.id &&
                    other.isEnabled &&
                    (other.observedText == target.observedText ||
                        (other.observedText == target.replacementText &&
                            other.replacementText == target.observedText))
            }
            require(!hasConflict) { "这个词条与另一个已启用词条冲突" }
            if (target.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM) {
                requireUserContextualCapacity(rules, target.id)
            }
            disableConflictingPersonalLearningRules(
                observedText = target.observedText,
                replacementText = target.replacementText,
                now = now,
            )
        }

        require(
            correctionDao.setUserDefinedRuleEnabled(
                id = ruleId,
                enabled = enabled,
                updatedAt = now,
            ) == 1,
        ) { "词条状态已变化，请重试" }
    }

    suspend fun setUserDefinedRuleMatchMode(
        ruleId: String,
        matchMode: CorrectionMatchMode,
        now: Long = System.currentTimeMillis(),
    ) = database.withTransaction {
        val target = correctionDao.getUserDefinedRules()
            .firstOrNull { it.id == ruleId }
            ?.toDomainRuleOrNull()
        requireNotNull(target) { "找不到这个自定义词条" }
        if (target.matchMode == matchMode) return@withTransaction
        if (target.isEnabled && matchMode == CorrectionMatchMode.CONTEXTUAL_LLM) {
            val rules = correctionDao.getUserDefinedRules()
                .mapNotNull { it.toDomainRuleOrNull() }
            requireUserContextualCapacity(rules, target.id)
        }

        disableConflictingPersonalLearningRules(
            observedText = target.observedText,
            replacementText = target.replacementText,
            now = now,
        )
        require(
            correctionDao.setUserDefinedRuleMatchMode(
                id = ruleId,
                matchMode = matchMode.name,
                updatedAt = now,
            ) == 1,
        ) { "词条状态已变化，请重试" }
    }

    suspend fun deleteUserDefinedRule(ruleId: String) = database.withTransaction {
        require(correctionDao.deleteUserDefinedRule(ruleId) == 1) {
            "找不到这个自定义词条"
        }
    }

    private suspend fun disableConflictingPersonalLearningRules(
        observedText: String,
        replacementText: String,
        now: Long,
    ) {
        personalCorrectionDao.getRulesConflictingWithUserDefinition(
            userObservedText = observedText,
            userReplacementText = replacementText,
        ).forEach { conflicting ->
            check(
                personalCorrectionDao.setProfileState(
                    ruleId = conflicting.id,
                    state = PersonalCorrectionLearningState.DISABLED.name,
                    updatedAt = now,
                ) == 1,
            ) { "冲突的个性化学习词条状态已变化" }
            check(
                personalCorrectionDao.setRuleEnabled(
                    ruleId = conflicting.id,
                    enabled = false,
                    updatedAt = now,
                ) == 1,
            ) { "冲突的个性化学习规则状态已变化" }
            personalCorrectionDao.failPendingEvents(
                ruleId = conflicting.id,
                reasonCode = "SUPERSEDED_BY_USER_DICTIONARY",
                updatedAt = now,
            )
        }
    }

    private fun requireUserContextualCapacity(
        rules: List<CorrectionRule>,
        excludingRuleId: String?,
    ) {
        val activeContextualRules = rules.count { rule ->
            rule.id != excludingRuleId &&
                rule.isEnabled &&
                rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM
        }
        if (activeContextualRules >= ContextualCorrectionLimits.MAX_ACTIVE_RULES_PER_ORIGIN) {
            throw ContextualCorrectionCapacityExceededException(
                origin = CorrectionRuleOrigin.USER_DEFINED,
                maximum = ContextualCorrectionLimits.MAX_ACTIVE_RULES_PER_ORIGIN,
            )
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

    suspend fun getRecordingRules(recordingId: String): List<CorrectionRule> =
        correctionDao.getRecordingRules(recordingId).mapNotNull { it.toDomainRuleOrNull() }

    fun observeRecordingRules(recordingId: String): Flow<List<CorrectionRule>> =
        correctionDao.observeRecordingRules(recordingId).map { rules ->
            rules.mapNotNull { it.toDomainRuleOrNull() }
        }

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

    private data class PersonalCorrectionNegativeFeedback(
        val matched: Boolean,
        val rawLearningRange: RawCodePointRange?,
    ) {
        companion object {
            val NONE = PersonalCorrectionNegativeFeedback(
                matched = false,
                rawLearningRange = null,
            )
        }
    }

    private companion object {
        const val INSERT_IGNORED = -1L
        const val MAX_PERSONAL_CORRECTION_PROFILES = 500
        const val MAX_PERSONAL_CORRECTION_EVENTS_PER_RULE = 20
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
    origin = strictEnumValueOf(origin, "correction rule origin"),
    scope = strictEnumValueOf(scope, "correction rule scope"),
    scopeId = scopeRecordingId,
    isEnabled = isEnabled
)

private fun CorrectionRuleEntity.toDomainRuleOrNull(): CorrectionRule? =
    runCatching { toDomainRule() }.getOrNull()

private fun List<CorrectionRule>.requireDecisionRule(ruleId: String?): CorrectionRule =
    firstOrNull { it.id == ruleId }
        ?: throw IllegalStateException("词条状态已变化，请重试")

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
