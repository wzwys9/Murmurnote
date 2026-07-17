package app.murmurnote.android.data.repository

import androidx.room.withTransaction
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.CorrectionDao
import app.murmurnote.android.data.local.dao.PersonalCorrectionDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
import app.murmurnote.android.data.local.entity.CorrectionRecordEntity
import app.murmurnote.android.data.local.entity.TranscriptRevisionEntity
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.domain.correction.AppliedTextReplacement
import app.murmurnote.android.domain.correction.CorrectionDecision
import app.murmurnote.android.domain.correction.CorrectionMatchMode
import app.murmurnote.android.domain.correction.CorrectionRule
import app.murmurnote.android.domain.correction.CorrectionRuleOrigin
import app.murmurnote.android.domain.correction.CorrectionScope
import app.murmurnote.android.domain.correction.ContextualCorrectionLimits
import app.murmurnote.android.domain.correction.CorrectedTextCoordinateMap
import app.murmurnote.android.domain.correction.CrossOriginCorrectionConflictPolicy
import app.murmurnote.android.domain.correction.PersonalCorrectionCandidate
import app.murmurnote.android.domain.correction.PersonalCorrectionCandidateFinder
import app.murmurnote.android.domain.correction.PersonalCorrectionContextHint
import app.murmurnote.android.domain.correction.PersonalCorrectionPlanValidator
import app.murmurnote.android.domain.correction.PersonalCorrectionProfile
import app.murmurnote.android.domain.correction.PersonalCorrectionRuleGraph
import app.murmurnote.android.domain.correction.PersonalCorrectionTextApplier
import app.murmurnote.android.domain.correction.PersonalCorrectionLearningState
import app.murmurnote.android.domain.correction.PersonalLearningConfidence
import app.murmurnote.android.domain.correction.PersonalLearningVerdict
import app.murmurnote.android.domain.correction.PinyinRelation
import app.murmurnote.android.domain.correction.ValidatedPersonalLearningDecision
import app.murmurnote.android.domain.transcript.CorrectionAuditReason
import app.murmurnote.android.domain.transcript.TranscriptRevisionSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PendingPersonalLearningObservation(
    val eventId: String,
    val ruleId: String,
    val observedText: String,
    val replacementText: String,
    val leftContext: String,
    val rightContext: String,
)

data class PersonalCorrectionSnapshot(
    val recordingId: String,
    val expectedRevision: Long,
    val candidates: List<PersonalCorrectionCandidate>,
)

@Singleton
class PersonalCorrectionRepository @Inject constructor(
    private val database: MurmurnoteDatabase,
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
    private val correctionDao: CorrectionDao,
    private val personalCorrectionDao: PersonalCorrectionDao,
) {
    suspend fun getPendingObservations(limit: Int): List<PendingPersonalLearningObservation> {
        require(limit in 1..MAX_PENDING_REVIEW_BATCH)
        return personalCorrectionDao
            .getPendingEvents(limit * MAX_EVENTS_PER_RULE)
            .distinctBy { it.ruleId }
            .take(limit)
            .mapNotNull { event ->
                val rule = correctionDao.getRule(event.ruleId) ?: return@mapNotNull null
                if (rule.origin != CorrectionRuleOrigin.PERSONAL_LEARNING.name) {
                    return@mapNotNull null
                }
                PendingPersonalLearningObservation(
                    eventId = event.id,
                    ruleId = event.ruleId,
                    observedText = rule.observedText,
                    replacementText = rule.replacementText,
                    leftContext = event.leftContext,
                    rightContext = event.rightContext,
                )
            }
    }

    suspend fun completeReview(
        observationId: String,
        observedPinyin: String?,
        replacementPinyin: String?,
        pinyinRelation: PinyinRelation,
        decision: ValidatedPersonalLearningDecision,
        now: Long = System.currentTimeMillis(),
    ): Boolean = database.withTransaction {
        if (decision.observationId != observationId) return@withTransaction false
        val event = personalCorrectionDao.getEvent(observationId)
            ?: return@withTransaction false
        if (event.status != "PENDING") return@withTransaction false
        val profile = personalCorrectionDao.getProfile(event.ruleId)
            ?: return@withTransaction false
        val rule = correctionDao.getRule(event.ruleId)
            ?: return@withTransaction false
        if (
            profile.ruleId != rule.id ||
            rule.matchMode != CorrectionMatchMode.CONTEXTUAL_LLM.name ||
            rule.origin != CorrectionRuleOrigin.PERSONAL_LEARNING.name
        ) {
            return@withTransaction false
        }

        val requestedActivation = decision.verdict == PersonalLearningVerdict.ACTIVATE &&
            decision.confidence == PersonalLearningConfidence.HIGH
        val keepExistingActivation =
            profile.state == PersonalCorrectionLearningState.ACTIVE.name && rule.isEnabled
        val activeRuleEntities = if (requestedActivation) {
            personalCorrectionDao.getActiveRules(MAX_STORED_RULES)
        } else {
            emptyList()
        }
        val conflictingRules = activeRuleEntities.filter { other ->
            other.id != event.ruleId && other.observedText == rule.observedText
        }
        val conflictingRuleIds = conflictingRules.mapTo(mutableSetOf()) { it.id }
        val entitiesAfterConflictRemoval = activeRuleEntities.filter { active ->
            active.id !in conflictingRuleIds && active.id != event.ruleId
        }
        val rulesAfterConflictRemoval = entitiesAfterConflictRemoval.mapNotNull {
            it.toDomainRule()
        }
        val learnedRule = rule.toDomainRule() ?: return@withTransaction false
        val conflictsWithUserDefinition = correctionDao
            .getEnabledUserDefinedRules()
            .asSequence()
            .mapNotNull { it.toDomainRule() }
            .any { userRule ->
                CrossOriginCorrectionConflictPolicy.conflicts(userRule, learnedRule)
            }
        val localBlockReason = when {
            !requestedActivation -> null
            conflictsWithUserDefinition -> "LOCAL_USER_DICTIONARY_CONFLICT"
            entitiesAfterConflictRemoval.size >= MAX_ACTIVE_RULES ->
                "LOCAL_ACTIVE_RULE_LIMIT"
            PersonalCorrectionRuleGraph.wouldCreateCycle(
                observedText = rule.observedText,
                replacementText = rule.replacementText,
                activeRules = rulesAfterConflictRemoval,
            ) -> "LOCAL_RULE_CYCLE"
            else -> null
        }
        val activate = requestedActivation && localBlockReason == null
        if (activate) {
            conflictingRules.forEach { conflicting ->
                check(
                    personalCorrectionDao.setProfileState(
                        conflicting.id,
                        PersonalCorrectionLearningState.DISABLED.name,
                        now,
                    ) == 1,
                ) { "Conflicting personal correction profile changed during review" }
                check(
                    personalCorrectionDao.setRuleEnabled(conflicting.id, false, now) == 1,
                ) { "Conflicting personal correction rule changed during review" }
                personalCorrectionDao.failPendingEvents(
                    ruleId = conflicting.id,
                    reasonCode = "SUPERSEDED_BY_CONFLICTING_ACTIVATION",
                    updatedAt = now,
                )
            }
        }
        val state = when {
            activate -> PersonalCorrectionLearningState.ACTIVE
            localBlockReason != null -> PersonalCorrectionLearningState.REJECTED
            keepExistingActivation -> PersonalCorrectionLearningState.ACTIVE
            decision.verdict == PersonalLearningVerdict.REJECT ->
                PersonalCorrectionLearningState.REJECTED
            else -> PersonalCorrectionLearningState.NEEDS_MORE_EVIDENCE
        }
        check(
            personalCorrectionDao.completeEventReview(
                eventId = observationId,
                pinyinRelation = pinyinRelation.name,
                verdict = decision.verdict.name,
                confidence = decision.confidence.name,
                reasonCode = decision.reasonCode,
                reviewedAt = now,
            ) == 1,
        ) { "Personal correction event changed during review" }
        check(
            personalCorrectionDao.completeProfileReview(
                ruleId = event.ruleId,
                state = state.name,
                observedPinyin = observedPinyin,
                replacementPinyin = replacementPinyin,
                pinyinRelation = pinyinRelation.name,
                verdict = decision.verdict.name,
                confidence = decision.confidence.name,
                reasonCode = localBlockReason ?: decision.reasonCode,
                reviewedAt = now,
            ) == 1,
        ) { "Personal correction profile changed during review" }
        check(
            personalCorrectionDao.setRuleEnabled(
                event.ruleId,
                activate || keepExistingActivation,
                now,
            ) == 1,
        ) {
            "Personal correction rule changed during review"
        }
        if (activate) {
            personalCorrectionDao.supersedeOtherPendingEvents(
                ruleId = event.ruleId,
                completedEventId = observationId,
                updatedAt = now,
            )
        } else if (localBlockReason != null) {
            personalCorrectionDao.failPendingEvents(
                ruleId = event.ruleId,
                reasonCode = localBlockReason,
                updatedAt = now,
            )
        }
        true
    }

    suspend fun prepareSnapshot(
        recordingId: String,
        includeUserDefinedRules: Boolean = false,
        includePersonalLearningRules: Boolean = true,
    ): PersonalCorrectionSnapshot =
        database.withTransaction {
            val recording = recordingDao.getById(recordingId)
                ?: throw TranscriptPersistenceException("Recording $recordingId does not exist")
            if (recording.rawTranscript == null) {
                return@withTransaction PersonalCorrectionSnapshot(
                    recordingId = recordingId,
                    expectedRevision = recording.correctionRevision,
                    candidates = emptyList(),
                )
            }
            if (recording.correctionRevision != BASE_TRANSCRIPT_REVISION) {
                return@withTransaction PersonalCorrectionSnapshot(
                    recordingId = recordingId,
                    expectedRevision = recording.correctionRevision,
                    candidates = emptyList(),
                )
            }
            val userDefinedRules = if (includeUserDefinedRules) {
                correctionDao.getEnabledUserContextualRules(
                    ContextualCorrectionLimits.MAX_ACTIVE_RULES_PER_ORIGIN,
                )
            } else {
                emptyList()
            }
            val personalLearningRules = if (includePersonalLearningRules) {
                personalCorrectionDao.getActiveRules(
                    ContextualCorrectionLimits.MAX_ACTIVE_RULES_PER_ORIGIN,
                )
            } else {
                emptyList()
            }
            val rules = (userDefinedRules + personalLearningRules)
                .distinctBy { it.id }
                .mapNotNull { it.toDomainRule() }
            val learnedRuleIds = rules
                .filter { it.origin == CorrectionRuleOrigin.PERSONAL_LEARNING }
                .map { it.id }
            val contextHints = if (learnedRuleIds.isEmpty()) {
                emptyMap()
            } else {
                personalCorrectionDao
                    .getReviewedEventsForRules(learnedRuleIds)
                    .distinctBy { it.ruleId }
                    .associate { event ->
                        event.ruleId to PersonalCorrectionContextHint(
                            leftContext = event.leftContext,
                            rightContext = event.rightContext,
                            pinyinRelation = enumValues<PinyinRelation>()
                                .firstOrNull { it.name == event.pinyinRelation }
                                ?: PinyinRelation.UNAVAILABLE,
                        )
                    }
            }
            val segments = transcriptDao.getSegments(recordingId)
            val appliedRecords = correctionDao.getAppliedRecordsForRevision(
                recordingId,
                BASE_TRANSCRIPT_REVISION,
            )
            var segmentRawStart = 0
            val candidates = segments.asSequence()
                .flatMap { segment ->
                    val currentRawStart = segmentRawStart
                    val currentRawEnd = currentRawStart + segment.rawText.codePointLength()
                    segmentRawStart = currentRawEnd + SEGMENT_SEPARATOR_CODE_POINTS
                    if (segment.isEdited) return@flatMap emptySequence()
                    val coordinateMap = CorrectedTextCoordinateMap.create(
                        rawText = segment.rawText,
                        correctedText = segment.correctedText,
                        replacements = appliedRecords
                            .asSequence()
                            .filter { record ->
                                record.rawStartCodePoint >= currentRawStart &&
                                    record.rawEndCodePointExclusive <= currentRawEnd
                            }
                            .map { record ->
                                AppliedTextReplacement(
                                    rawStartCodePoint =
                                        record.rawStartCodePoint - currentRawStart,
                                    rawEndCodePointExclusive =
                                        record.rawEndCodePointExclusive - currentRawStart,
                                    originalText = record.originalText,
                                    replacementText = record.replacementText,
                                )
                            }
                            .toList(),
                    ) ?: return@flatMap emptySequence()
                    PersonalCorrectionCandidateFinder.find(
                        segmentId = segment.id,
                        text = segment.correctedText,
                        rules = rules,
                        contextHints = contextHints,
                    ).asSequence().mapNotNull { candidate ->
                        val rawRange = coordinateMap.rawRangeForCorrected(
                            candidate.startCodePoint,
                            candidate.endCodePointExclusive,
                        ) ?: return@mapNotNull null
                        if (
                            segment.rawText.sliceCodePoints(
                                rawRange.startCodePoint,
                                rawRange.endCodePointExclusive,
                            ) != candidate.observedText
                        ) {
                            return@mapNotNull null
                        }
                        candidate.copy(
                            rawStartCodePoint = rawRange.startCodePoint,
                            rawEndCodePointExclusive = rawRange.endCodePointExclusive,
                        )
                    }
                }
                .take(PersonalCorrectionPlanValidator.MAX_CANDIDATES_PER_RECORDING)
                .toList()
            PersonalCorrectionSnapshot(
                recordingId = recordingId,
                expectedRevision = recording.correctionRevision,
                candidates = candidates,
            )
        }

    suspend fun applyApproved(
        snapshot: PersonalCorrectionSnapshot,
        approved: List<PersonalCorrectionCandidate>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (approved.isEmpty()) return false
        val snapshotById = snapshot.candidates.associateBy { it.id }
        val validated = approved.mapNotNull { candidate ->
            snapshotById[candidate.id]?.takeIf { it == candidate }
        }
        if (validated.size != approved.size) return false

        return database.withTransaction {
            val recording = recordingDao.getById(snapshot.recordingId)
                ?: return@withTransaction false
            if (
                recording.rawTranscript == null ||
                recording.correctionRevision != snapshot.expectedRevision ||
                snapshot.expectedRevision != BASE_TRANSCRIPT_REVISION ||
                recording.correctionRevision == Long.MAX_VALUE
            ) {
                return@withTransaction false
            }
            val ruleIds = validated.map { it.ruleId }.distinct()
            val currentRules = correctionDao.getRules(ruleIds).associateBy { it.id }
            if (currentRules.size != ruleIds.size) return@withTransaction false
            val learnedRuleIds = validated
                .filter { it.ruleOrigin == CorrectionRuleOrigin.PERSONAL_LEARNING }
                .map { it.ruleId }
                .distinct()
            val activeLearningProfiles = if (learnedRuleIds.isEmpty()) {
                emptyMap()
            } else {
                personalCorrectionDao.getProfiles(learnedRuleIds).associateBy { it.ruleId }
            }
            val rulesStillActive = validated.all { candidate ->
                val rule = currentRules.getValue(candidate.ruleId)
                val baseRuleMatches = rule.isEnabled &&
                    rule.scope == CorrectionScope.GLOBAL.name &&
                    rule.scopeRecordingId == null &&
                    rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM.name &&
                    rule.origin == candidate.ruleOrigin.name &&
                    rule.observedText == candidate.observedText &&
                    rule.replacementText == candidate.replacementText
                if (!baseRuleMatches) {
                    false
                } else if (candidate.ruleOrigin == CorrectionRuleOrigin.PERSONAL_LEARNING) {
                    activeLearningProfiles[candidate.ruleId]?.state ==
                        PersonalCorrectionLearningState.ACTIVE.name
                } else {
                    true
                }
            }
            if (!rulesStillActive) return@withTransaction false
            val segments = transcriptDao.getSegments(snapshot.recordingId)
            val byId = segments.associateBy { it.id }
            val grouped = validated.groupBy { it.segmentId }
            val correctedBySegment = mutableMapOf<Long, String>()
            for ((segmentId, candidates) in grouped) {
                val segment = byId[segmentId] ?: return@withTransaction false
                if (segment.isEdited) {
                    return@withTransaction false
                }
                candidates.forEach { candidate ->
                    if (
                        segment.rawText.sliceCodePoints(
                            candidate.rawStartCodePoint,
                            candidate.rawEndCodePointExclusive,
                        ) != candidate.observedText
                    ) {
                        return@withTransaction false
                    }
                }
                val corrected = runCatching {
                    PersonalCorrectionTextApplier.apply(segment.correctedText, candidates)
                }.getOrElse { return@withTransaction false }
                correctedBySegment[segmentId] = corrected
            }

            val newRevision = recording.correctionRevision + 1
            for ((segmentId, correctedText) in correctedBySegment) {
                val segment = byId.getValue(segmentId)
                if (
                    transcriptDao.setAutomatedCorrection(
                        recordingId = snapshot.recordingId,
                        segmentId = segmentId,
                        expectedSegmentRevision = segment.correctionRevision,
                        correctedText = correctedText,
                        newRevision = newRevision,
                    ) != 1
                ) {
                    error("Transcript segment changed during personalized correction")
                }
            }

            val updatedSegments = transcriptDao.getSegments(snapshot.recordingId)
            val correctedTranscript = updatedSegments.joinToString(SEGMENT_SEPARATOR) {
                it.correctedText
            }
            if (
                recordingDao.updateCorrectedTranscriptRevision(
                    id = snapshot.recordingId,
                    expectedRevision = recording.correctionRevision,
                    newRevision = newRevision,
                    correctedTranscript = correctedTranscript,
                    transcriptDirty = recording.hasSummarySnapshot(),
                    editedAt = now,
                ) != 1
            ) {
                error("Recording changed during personalized correction")
            }
            transcriptDao.insertRevision(
                TranscriptRevisionEntity(
                    recordingId = snapshot.recordingId,
                    revision = newRevision,
                    text = correctedTranscript,
                    source = TranscriptRevisionSource.PERSONALIZED_LLM.name,
                    createdAt = now,
                ),
            )

            val rawOffsets = mutableMapOf<Long, Int>()
            var rawCursor = 0
            segments.forEachIndexed { index, segment ->
                rawOffsets[segment.id] = rawCursor
                rawCursor += segment.rawText.codePointCount(0, segment.rawText.length)
                if (index < segments.lastIndex) rawCursor += SEGMENT_SEPARATOR_CODE_POINTS
            }
            correctionDao.insertRecords(
                validated.map { candidate ->
                    val rawStart = rawOffsets.getValue(candidate.segmentId) +
                        candidate.rawStartCodePoint
                    CorrectionRecordEntity(
                        recordingId = snapshot.recordingId,
                        revision = newRevision,
                        sourceRuleId = candidate.ruleId,
                        rawStartCodePoint = rawStart,
                        rawEndCodePointExclusive = rawStart +
                            (candidate.rawEndCodePointExclusive -
                                candidate.rawStartCodePoint),
                        originalText = candidate.observedText,
                        replacementText = candidate.replacementText,
                        decision = CorrectionDecision.APPLIED.name,
                        reason = CorrectionAuditReason.PERSONALIZED_LLM_CONTEXT_APPLIED.name,
                        createdAt = now,
                    )
                },
            )
            true
        }
    }

    fun observeProfiles(): Flow<List<PersonalCorrectionProfile>> =
        personalCorrectionDao.observeProfilesWithRules().map { rows ->
            rows.mapNotNull { row ->
                val state = enumValues<PersonalCorrectionLearningState>()
                    .firstOrNull { it.name == row.profile.state }
                    ?: return@mapNotNull null
                if (row.rule.origin != CorrectionRuleOrigin.PERSONAL_LEARNING.name) {
                    return@mapNotNull null
                }
                PersonalCorrectionProfile(
                    ruleId = row.rule.id,
                    observedText = row.rule.observedText,
                    replacementText = row.rule.replacementText,
                    state = state,
                    positiveEvidenceCount = row.profile.positiveEvidenceCount,
                    negativeEvidenceCount = row.profile.negativeEvidenceCount,
                    pinyinRelation = enumValues<PinyinRelation>()
                        .firstOrNull { it.name == row.profile.pinyinRelation }
                        ?: PinyinRelation.UNAVAILABLE,
                    lastVerdict = row.profile.lastVerdict?.let { value ->
                        enumValues<PersonalLearningVerdict>().firstOrNull { it.name == value }
                    },
                    lastConfidence = row.profile.lastConfidence?.let { value ->
                        enumValues<PersonalLearningConfidence>().firstOrNull { it.name == value }
                    },
                    lastReasonCode = row.profile.lastReasonCode,
                    isEnabled = row.rule.isEnabled && state == PersonalCorrectionLearningState.ACTIVE,
                )
            }
        }

    suspend fun setProfileEnabled(
        ruleId: String,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) = database.withTransaction {
        val profile = personalCorrectionDao.getProfile(ruleId)
            ?: throw IllegalArgumentException("找不到这个学习词条")
        val rule = correctionDao.getRule(ruleId)
            ?: throw IllegalArgumentException("找不到这个学习词条")
        require(rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM.name) {
            "这个词条不属于个性化纠错"
        }
        require(rule.origin == CorrectionRuleOrigin.PERSONAL_LEARNING.name) {
            "这个词条不属于个性化纠错"
        }
        if (enabled) {
            require(
                profile.state == PersonalCorrectionLearningState.DISABLED.name &&
                profile.lastVerdict == PersonalLearningVerdict.ACTIVATE.name &&
                    profile.lastConfidence == PersonalLearningConfidence.HIGH.name,
            ) { "这个词条还没有通过高置信上下文评估" }
            val activeRuleEntities = personalCorrectionDao.getActiveRules(MAX_STORED_RULES)
            val conflict = activeRuleEntities.any { other ->
                other.id != ruleId && other.observedText == rule.observedText
            }
            require(!conflict) { "同一识别结果已有另一个启用写法" }
            require(activeRuleEntities.size < MAX_ACTIVE_RULES) { "启用词条已达到上限" }
            val learnedRule = checkNotNull(rule.toDomainRule()) { "学习词条数据无效" }
            val conflictsWithUserDefinition = correctionDao.getEnabledUserDefinedRules()
                .asSequence()
                .mapNotNull { it.toDomainRule() }
                .any { userRule ->
                    CrossOriginCorrectionConflictPolicy.conflicts(userRule, learnedRule)
                }
            require(!conflictsWithUserDefinition) { "这个词条与自定义纠错词典冲突" }
            require(
                !PersonalCorrectionRuleGraph.wouldCreateCycle(
                    observedText = rule.observedText,
                    replacementText = rule.replacementText,
                    activeRules = activeRuleEntities.mapNotNull { it.toDomainRule() },
                ),
            ) { "这个词条会与已启用词条形成循环替换" }
        }
        val state = if (enabled) {
            PersonalCorrectionLearningState.ACTIVE
        } else {
            PersonalCorrectionLearningState.DISABLED
        }
        check(personalCorrectionDao.setProfileState(ruleId, state.name, now) == 1)
        check(personalCorrectionDao.setRuleEnabled(ruleId, enabled, now) == 1)
        if (!enabled) {
            personalCorrectionDao.failPendingEvents(
                ruleId = ruleId,
                reasonCode = "DISABLED_BY_USER",
                updatedAt = now,
            )
        }
    }

    suspend fun deleteProfile(ruleId: String) = database.withTransaction {
        require(personalCorrectionDao.deleteRule(ruleId) == 1) { "找不到这个学习词条" }
    }

    suspend fun clearProfiles() = database.withTransaction {
        personalCorrectionDao.deleteAllRules()
    }

    private fun Recording.hasSummarySnapshot(): Boolean =
        summary != null || draftSummary != null || finalSummary != null

    private fun String.sliceCodePoints(start: Int, endExclusive: Int): String {
        if (start < 0 || endExclusive < start || endExclusive > codePointLength()) return ""
        val startChar = offsetByCodePoints(0, start)
        val endChar = offsetByCodePoints(startChar, endExclusive - start)
        return substring(startChar, endChar)
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun app.murmurnote.android.data.local.entity.CorrectionRuleEntity.toDomainRule():
        CorrectionRule? = runCatching {
            CorrectionRule(
                id = id,
                observedText = observedText,
                replacementText = replacementText,
                matchMode = CorrectionMatchMode.valueOf(matchMode),
                origin = CorrectionRuleOrigin.valueOf(origin),
                scope = CorrectionScope.valueOf(scope),
                scopeId = scopeRecordingId,
                isEnabled = isEnabled,
            )
        }.getOrNull()

    private companion object {
        const val MAX_PENDING_REVIEW_BATCH = 3
        const val MAX_EVENTS_PER_RULE = 20
        const val MAX_ACTIVE_RULES = 100
        const val MAX_STORED_RULES = 500
        const val BASE_TRANSCRIPT_REVISION = 0L
        const val SEGMENT_SEPARATOR = "\n"
        const val SEGMENT_SEPARATOR_CODE_POINTS = 1
    }
}
