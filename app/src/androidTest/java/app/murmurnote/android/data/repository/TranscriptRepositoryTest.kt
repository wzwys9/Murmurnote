package app.murmurnote.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.AsrProvenance
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.domain.correction.CorrectionScope
import app.murmurnote.android.domain.correction.PersonalLearningConfidence
import app.murmurnote.android.domain.correction.PersonalLearningVerdict
import app.murmurnote.android.domain.correction.PinyinRelation
import app.murmurnote.android.domain.correction.ValidatedPersonalLearningDecision
import app.murmurnote.android.domain.transcript.CorrectionAuditReason
import app.murmurnote.android.domain.transcript.ModelTranscriptSegment
import app.murmurnote.android.domain.transcript.ModelTranscriptBoundary
import app.murmurnote.android.domain.transcript.ModelSegmentCutReason
import app.murmurnote.android.domain.transcript.TranscriptRevisionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptRepositoryTest {
    private lateinit var database: MurmurnoteDatabase
    private lateinit var repository: TranscriptRepository
    private lateinit var personalCorrectionRepository: PersonalCorrectionRepository
    private lateinit var summaryRepository: SummaryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MurmurnoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TranscriptRepository(
            database = database,
            recordingDao = database.recordingDao(),
            transcriptDao = database.transcriptDao(),
            correctionDao = database.correctionDao(),
            personalCorrectionDao = database.personalCorrectionDao(),
        )
        personalCorrectionRepository = PersonalCorrectionRepository(
            database = database,
            recordingDao = database.recordingDao(),
            transcriptDao = database.transcriptDao(),
            correctionDao = database.correctionDao(),
            personalCorrectionDao = database.personalCorrectionDao(),
        )
        summaryRepository = SummaryRepository(
            database = database,
            recordingDao = database.recordingDao(),
            itemDao = database.itemDao(),
            transcriptDao = database.transcriptDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cacheModelSegment_sameAttemptIsIdempotent() = runBlocking {
        insertRecording()
        val segment = modelSegment(rawText = "原始🙂", sequence = 0)

        val first = repository.cacheModelSegment(RECORDING_ID, segment, PROVENANCE)
        val retry = repository.cacheModelSegment(RECORDING_ID, segment, PROVENANCE)

        assertTrue(first.id > 0)
        assertEquals(first.id, retry.id)
        assertEquals(listOf("原始🙂"), repository.getSegments(RECORDING_ID).map { it.rawText })
    }

    @Test
    fun cacheModelSegment_preservesEmptyAndBlankModelSegments() = runBlocking {
        insertRecording()

        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "", sequence = 0),
            PROVENANCE
        )
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = " \t", sequence = 1),
            PROVENANCE
        )

        val stored = repository.getSegments(RECORDING_ID)
        assertEquals(listOf(0, 1), stored.map { it.sequence })
        assertEquals(listOf("", " \t"), stored.map { it.rawText })
    }

    @Test
    fun cacheModelSegment_conflictRollsBackAndNeverChangesRaw() = runBlocking {
        insertRecording()
        val original = modelSegment(rawText = "immutable raw", sequence = 0)
        repository.cacheModelSegment(RECORDING_ID, original, PROVENANCE)

        val error = assertThrows(TranscriptSegmentConflictException::class.java) {
            runBlocking {
                repository.cacheModelSegment(
                    RECORDING_ID,
                    original.copy(rawText = "attempted overwrite"),
                    PROVENANCE
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("sequence 0"))
        val stored = repository.getSegments(RECORDING_ID).single()
        assertEquals("immutable raw", stored.rawText)
        assertEquals("immutable raw", stored.correctedText)
        assertEquals(1, repository.getSegments(RECORDING_ID).size)
    }

    @Test
    fun cacheModelSegment_afterFinalizeIsRejectedWithoutChangingSegments() = runBlocking {
        insertFinalizedSingleSegment("immutable")

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.cacheModelSegment(
                    RECORDING_ID,
                    modelSegment("late", sequence = 1),
                    PROVENANCE
                )
            }
        }

        assertEquals(listOf("immutable"), repository.getSegments(RECORDING_ID).map { it.rawText })
    }

    @Test
    fun prepareModelAttempt_reusesOnlyExactFingerprintAndBoundaries() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(RECORDING_ID, modelSegment("first", 0), PROVENANCE)

        val reused = repository.prepareModelAttempt(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            plannedSegments = listOf(ModelTranscriptBoundary(0, 0, 500))
        )
        val cleared = repository.prepareModelAttempt(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE.copy(configFingerprint = "another-attempt"),
            plannedSegments = listOf(ModelTranscriptBoundary(0, 0, 500))
        )

        assertEquals(listOf("first"), reused.map { it.rawText })
        assertTrue(cleared.isEmpty())
        assertTrue(repository.getSegments(RECORDING_ID).isEmpty())
    }

    @Test
    fun prepareModelAttempt_discardsMatchingRowsWhenSourceCachesWereRegenerated() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(RECORDING_ID, modelSegment("old source", 0), PROVENANCE)

        val prepared = repository.prepareModelAttempt(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            plannedSegments = listOf(ModelTranscriptBoundary(0, 0, 500)),
            allowProvisionalReuse = false
        )

        assertTrue(prepared.isEmpty())
        assertTrue(repository.getSegments(RECORDING_ID).isEmpty())
    }

    @Test
    fun finalize_emptyNeuralVadPlanPersistsACompleteEmptyRevision() = runBlocking {
        insertRecording()
        repository.prepareModelAttempt(RECORDING_ID, PROVENANCE, emptyList())

        repository.finalizeModelTranscript(RECORDING_ID, PROVENANCE, emptyList(), now = 9L)

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("", recording.rawTranscript)
        assertEquals("", recording.correctedTranscript)
        assertEquals("", repository.getRevisions(RECORDING_ID).single().text)
        assertEquals(PROVENANCE.configFingerprint, recording.asrConfigFingerprint)
    }

    @Test
    fun finalize_marksAnUnboundPreexistingSummaryStale() = runBlocking {
        database.recordingDao().insert(
            Recording(
                id = RECORDING_ID,
                title = "Legacy summary only",
                originalFilePath = "/test.wav",
                durationMs = 1_000,
                createdAt = 0,
                source = RecordingSource.RECORDED,
                processingStatus = ProcessingStatus.TRANSCRIBING,
                summary = "old summary",
                finalSummary = "old summary"
            )
        )

        repository.finalizeModelTranscript(RECORDING_ID, PROVENANCE, emptyList(), now = 11L)

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("old summary", recording.finalSummary)
        assertNull(recording.summaryTranscriptRevision)
        assertTrue(recording.transcriptDirty)
    }

    @Test
    fun finalize_appliesRulesPerSegmentAndPersistsFrozenProvenance() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "alpha", sequence = 0, startMs = 0, endMs = 500),
            PROVENANCE
        )
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "beta alpha", sequence = 1, startMs = 500, endMs = 1_000)
                .copy(
                    cutReason = ModelSegmentCutReason.FALLBACK_HARD_LIMIT,
                    overlapBeforeMs = 500
                ),
            PROVENANCE
        )
        repository.createGlobalLexiconRule("alpha", "omega", now = 10L)

        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0, 1),
            applyGlobalLexicon = true,
            now = 20L
        )

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("alpha\nbeta alpha", recording.rawTranscript)
        assertEquals("omega\nbeta omega", recording.correctedTranscript)
        assertEquals(0L, recording.correctionRevision)
        assertEquals(PROVENANCE.engineType.name, recording.asrEngineType)
        assertEquals(PROVENANCE.modelId, recording.asrModelId)
        assertEquals(PROVENANCE.configFingerprint, recording.asrConfigFingerprint)
        assertEquals(PROVENANCE.configSnapshotJson, recording.asrConfigSnapshotJson)
        assertEquals(PROVENANCE.vadPresetVersion, recording.vadPresetVersion)
        assertEquals(
            listOf("omega", "beta omega"),
            repository.getSegments(RECORDING_ID).map { it.correctedText },
        )
        val hardCut = repository.getSegments(RECORDING_ID).last()
        assertEquals(ModelSegmentCutReason.FALLBACK_HARD_LIMIT.name, hardCut.cutReason)
        assertEquals(500L, hardCut.overlapBeforeMs)
        assertEquals(PROVENANCE.vadPresetVersion, hardCut.vadPresetVersion)

        val revisions = repository.getRevisions(RECORDING_ID)
        assertEquals(1, revisions.size)
        assertEquals(TranscriptRevisionSource.MODEL_FINAL, revisions.single().source)
        assertEquals("omega\nbeta omega", revisions.single().text)
        val audit = repository.getAuditRecords(RECORDING_ID)
        assertEquals(listOf(0, 11), audit.map { it.rawStartCodePoint })
        assertTrue(audit.all { it.sourceRuleId != null })
    }

    @Test
    fun finalize_skipsGlobalLexiconWhenItsMasterSwitchIsOff() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "alpha", sequence = 0),
            PROVENANCE,
        )
        repository.createGlobalLexiconRule("alpha", "omega", now = 21L)

        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0),
            applyGlobalLexicon = false,
            now = 22L,
        )

        val stored = repository.getSegments(RECORDING_ID).single()
        assertEquals("alpha", stored.rawText)
        assertEquals("alpha", stored.correctedText)
        assertTrue(repository.getAuditRecords(RECORDING_ID).isEmpty())
    }

    @Test
    fun finalize_keepsExistingRecordingRulesWhenGlobalLexiconIsOff() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "alpha", sequence = 0),
            PROVENANCE,
        )
        repository.rememberRecordingRule(
            recordingId = RECORDING_ID,
            diff = app.murmurnote.android.domain.correction.SingleReplacementDiff(
                startCodePoint = 0,
                endCodePointExclusive = 5,
                observedText = "alpha",
                replacementText = "omega",
                eligibleForRule = true,
            ),
            now = 23L,
        )

        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0),
            applyGlobalLexicon = false,
            now = 24L,
        )

        assertEquals("omega", repository.getSegments(RECORDING_ID).single().correctedText)
    }

    @Test
    fun editSegment_allowsArbitraryUnicodePreservesRawAndSnapshotsRevision() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val newText = "随便🙂\n第二行"

        val candidate = repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = newText,
            now = 30L
        )

        assertNotNull(candidate)
        val stored = repository.getSegments(RECORDING_ID).single()
        assertEquals("alpha", stored.rawText)
        assertEquals(newText, stored.correctedText)
        assertTrue(stored.isEdited)
        assertEquals(1L, stored.correctionRevision)

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("alpha", recording.rawTranscript)
        assertEquals(newText, recording.correctedTranscript)
        assertEquals(1L, recording.correctionRevision)
        assertFalse(recording.transcriptDirty)
        assertEquals(30L, recording.transcriptEditedAt)

        val revisions = repository.getRevisions(RECORDING_ID)
        assertEquals(listOf(0L, 1L), revisions.map { it.revision })
        assertEquals(listOf("alpha", newText), revisions.map { it.text })
        assertEquals(
            listOf(TranscriptRevisionSource.MODEL_FINAL, TranscriptRevisionSource.MANUAL_EDIT),
            revisions.map { it.source }
        )
        assertTrue(repository.getRecordingRules(RECORDING_ID).isEmpty())
        val manualAudit = repository.getAuditRecords(RECORDING_ID).last()
        assertNull(manualAudit.sourceRuleId)
        assertEquals(CorrectionAuditReason.MANUAL_EDIT, manualAudit.reason)
    }

    @Test
    fun rememberRecordingRule_requiresExplicitCallAndDoesNotRewriteHistory() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val diff = repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "omega",
            now = 40L
        )!!
        assertTrue(repository.getRecordingRules(RECORDING_ID).isEmpty())

        val rule = repository.rememberRecordingRule(
            recordingId = RECORDING_ID,
            diff = diff,
            now = 41L
        )

        assertEquals(CorrectionScope.RECORDING, rule.scope)
        assertEquals(RECORDING_ID, rule.scopeId)
        assertEquals("omega", repository.getSegments(RECORDING_ID).single().correctedText)
        assertEquals(1, repository.getRecordingRules(RECORDING_ID).size)
        assertEquals(2, repository.getRevisions(RECORDING_ID).size)
    }

    @Test
    fun editSegment_whenEnabledPersistsAPendingLearningSampleInTheSameRevision() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")

        repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "这是声记应用",
            capturePersonalLearning = true,
            now = 45L,
        )

        val event = database.personalCorrectionDao().getPendingEvents(limit = 5).single()
        val profile = database.personalCorrectionDao().getProfile(event.ruleId)!!
        val rule = database.correctionDao().getRule(event.ruleId)!!
        assertEquals(segmentId, event.segmentId)
        assertEquals(1L, event.revision)
        assertEquals("这是", event.leftContext)
        assertEquals("记应用", event.rightContext)
        assertEquals("PENDING", event.status)
        assertEquals("PENDING_REVIEW", profile.state)
        assertEquals(1, profile.positiveEvidenceCount)
        assertEquals("生", rule.observedText)
        assertEquals("声", rule.replacementText)
        assertEquals("CONTEXTUAL_LLM", rule.matchMode)
        assertFalse(rule.isEnabled)
    }

    @Test
    fun editSegment_whenLearningIsOffDoesNotCreatePersonalKnowledge() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")

        repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "这是声记应用",
            capturePersonalLearning = false,
            now = 46L,
        )

        assertTrue(database.personalCorrectionDao().getPendingEvents(limit = 5).isEmpty())
        assertTrue(database.personalCorrectionDao().getActiveRules().isEmpty())
    }

    @Test
    fun repeatedPersonalEditReusesTheRuleAndAddsEvidence() = runBlocking {
        val firstSegment = insertFinalizedSingleSegment("这是生记应用")
        repository.editSegment(
            RECORDING_ID,
            firstSegment,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 47L,
        )
        val firstEvent = database.personalCorrectionDao().getPendingEvents(5).single()

        val secondRecordingId = "second-recording"
        database.recordingDao().insert(
            Recording(
                id = secondRecordingId,
                title = "Second",
                originalFilePath = "/second.wav",
                durationMs = 1_000,
                createdAt = 48L,
                source = RecordingSource.RECORDED,
                processingStatus = ProcessingStatus.COMPLETED,
                rawTranscript = "这是生记应用",
                correctedTranscript = "这是生记应用",
            ),
        )
        val secondSegment = database.transcriptDao().insertModelSegment(
            app.murmurnote.android.data.local.entity.TranscriptSegment(
                recordingId = secondRecordingId,
                rawText = "这是生记应用",
                correctedText = "这是生记应用",
                startMs = 0,
                endMs = 1_000,
                sequence = 0,
                asrConfigFingerprint = PROVENANCE.configFingerprint,
                vadPresetVersion = PROVENANCE.vadPresetVersion,
            ),
        )
        database.transcriptDao().insertRevision(
            app.murmurnote.android.data.local.entity.TranscriptRevisionEntity(
                recordingId = secondRecordingId,
                revision = 0,
                text = "这是生记应用",
                source = TranscriptRevisionSource.MODEL_FINAL.name,
                createdAt = 48L,
            ),
        )

        repository.editSegment(
            secondRecordingId,
            secondSegment,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 49L,
        )

        val events = database.personalCorrectionDao().getPendingEvents(5)
        assertEquals(2, events.size)
        assertTrue(events.all { it.ruleId == firstEvent.ruleId })
        assertEquals(
            2,
            database.personalCorrectionDao().getProfile(firstEvent.ruleId)!!.positiveEvidenceCount,
        )

        assertTrue(
            personalCorrectionRepository.completeReview(
                observationId = firstEvent.id,
                observedPinyin = "sheng",
                replacementPinyin = "sheng",
                pinyinRelation = PinyinRelation.EXACT_PINYIN,
                decision = ValidatedPersonalLearningDecision(
                    observationId = firstEvent.id,
                    verdict = PersonalLearningVerdict.ACTIVATE,
                    confidence = PersonalLearningConfidence.HIGH,
                    reasonCode = "PHONETIC_ASR_ERROR",
                ),
                now = 50L,
            ),
        )
        assertTrue(database.personalCorrectionDao().getPendingEvents(5).isEmpty())
    }

    @Test
    fun highConfidenceLearningReviewActivatesOnlyThePersistedCandidate() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        repository.editSegment(
            RECORDING_ID,
            segmentId,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 52L,
        )
        val pending = personalCorrectionRepository.getPendingObservations(3).single()

        assertTrue(
            personalCorrectionRepository.completeReview(
                observationId = pending.eventId,
                observedPinyin = "sheng",
                replacementPinyin = "sheng",
                pinyinRelation = PinyinRelation.EXACT_PINYIN,
                decision = ValidatedPersonalLearningDecision(
                    observationId = pending.eventId,
                    verdict = PersonalLearningVerdict.ACTIVATE,
                    confidence = PersonalLearningConfidence.HIGH,
                    reasonCode = "PHONETIC_ASR_ERROR",
                ),
                now = 53L,
            ),
        )

        val activeRule = database.personalCorrectionDao().getActiveRules().single()
        assertEquals(pending.ruleId, activeRule.id)
        assertTrue(activeRule.isEnabled)
        val profile = database.personalCorrectionDao().getProfile(pending.ruleId)!!
        assertEquals("ACTIVE", profile.state)
        assertEquals("EXACT_PINYIN", profile.pinyinRelation)
        assertEquals("ACTIVATE", profile.lastVerdict)
        val event = database.personalCorrectionDao().getEvent(pending.eventId)!!
        assertEquals("REVIEWED", event.status)
        assertEquals("HIGH", event.llmConfidence)
    }

    @Test
    fun nonHighLearningReviewCannotEnableAContextualRule() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        repository.editSegment(
            RECORDING_ID,
            segmentId,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 54L,
        )
        val pending = personalCorrectionRepository.getPendingObservations(3).single()

        personalCorrectionRepository.completeReview(
            observationId = pending.eventId,
            observedPinyin = "sheng",
            replacementPinyin = "sheng",
            pinyinRelation = PinyinRelation.EXACT_PINYIN,
            decision = ValidatedPersonalLearningDecision(
                observationId = pending.eventId,
                verdict = PersonalLearningVerdict.ACTIVATE,
                confidence = PersonalLearningConfidence.MEDIUM,
                reasonCode = "PHONETIC_ASR_ERROR",
            ),
            now = 55L,
        )

        assertTrue(database.personalCorrectionDao().getActiveRules().isEmpty())
        val rule = database.correctionDao().getRule(pending.ruleId)!!
        val profile = database.personalCorrectionDao().getProfile(pending.ruleId)!!
        assertFalse(rule.isEnabled)
        assertEquals("NEEDS_MORE_EVIDENCE", profile.state)
    }

    @Test
    fun highConfidenceReviewStillRejectsAReverseReplacementCycle() = runBlocking {
        insertActivePersonalRule("existing-rule", "甲", "乙")
        val segmentId = insertFinalizedSingleSegment("乙")
        repository.editSegment(
            RECORDING_ID,
            segmentId,
            "甲",
            capturePersonalLearning = true,
            now = 55L,
        )
        val pending = personalCorrectionRepository.getPendingObservations(3).single()

        assertTrue(
            personalCorrectionRepository.completeReview(
                observationId = pending.eventId,
                observedPinyin = "yi",
                replacementPinyin = "jia",
                pinyinRelation = PinyinRelation.NOT_PHONETIC,
                decision = ValidatedPersonalLearningDecision(
                    observationId = pending.eventId,
                    verdict = PersonalLearningVerdict.ACTIVATE,
                    confidence = PersonalLearningConfidence.HIGH,
                    reasonCode = "USER_TERM_FITS_CONTEXT",
                ),
                now = 56L,
            ),
        )

        val newRule = database.correctionDao().getRule(pending.ruleId)!!
        val newProfile = database.personalCorrectionDao().getProfile(pending.ruleId)!!
        assertFalse(newRule.isEnabled)
        assertEquals("REJECTED", newProfile.state)
        assertEquals("LOCAL_RULE_CYCLE", newProfile.lastReasonCode)
        assertTrue(database.correctionDao().getRule("existing-rule")!!.isEnabled)
    }

    @Test
    fun learningReviewIsIdempotentAndCannotBeReplacedByALateResponse() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        repository.editSegment(
            RECORDING_ID,
            segmentId,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 56L,
        )
        val pending = personalCorrectionRepository.getPendingObservations(3).single()
        val decision = ValidatedPersonalLearningDecision(
            observationId = pending.eventId,
            verdict = PersonalLearningVerdict.ACTIVATE,
            confidence = PersonalLearningConfidence.HIGH,
            reasonCode = "PHONETIC_ASR_ERROR",
        )

        assertTrue(
            personalCorrectionRepository.completeReview(
                pending.eventId,
                "sheng",
                "sheng",
                PinyinRelation.EXACT_PINYIN,
                decision,
                now = 57L,
            ),
        )
        assertFalse(
            personalCorrectionRepository.completeReview(
                pending.eventId,
                "other",
                "other",
                PinyinRelation.NOT_PHONETIC,
                decision.copy(
                    verdict = PersonalLearningVerdict.REJECT,
                    reasonCode = "NOT_AN_ASR_ERROR",
                ),
                now = 58L,
            ),
        )
        assertEquals(
            "ACTIVE",
            database.personalCorrectionDao().getProfile(pending.ruleId)!!.state,
        )
    }

    @Test
    fun disablingAPendingProfileInvalidatesALateActivationResponse() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        repository.editSegment(
            RECORDING_ID,
            segmentId,
            "这是声记应用",
            capturePersonalLearning = true,
            now = 58L,
        )
        val pending = personalCorrectionRepository.getPendingObservations(3).single()

        personalCorrectionRepository.setProfileEnabled(
            ruleId = pending.ruleId,
            enabled = false,
            now = 59L,
        )

        assertFalse(
            personalCorrectionRepository.completeReview(
                observationId = pending.eventId,
                observedPinyin = "sheng",
                replacementPinyin = "sheng",
                pinyinRelation = PinyinRelation.EXACT_PINYIN,
                decision = ValidatedPersonalLearningDecision(
                    observationId = pending.eventId,
                    verdict = PersonalLearningVerdict.ACTIVATE,
                    confidence = PersonalLearningConfidence.HIGH,
                    reasonCode = "PHONETIC_ASR_ERROR",
                ),
                now = 60L,
            ),
        )
        assertTrue(database.personalCorrectionDao().getPendingEvents(3).isEmpty())
        assertFalse(database.correctionDao().getRule(pending.ruleId)!!.isEnabled)
        assertEquals(
            "DISABLED",
            database.personalCorrectionDao().getProfile(pending.ruleId)!!.state,
        )
    }

    @Test
    fun approvedPersonalCorrectionCreatesAnAuditedRevisionAndPreservesRaw() = runBlocking {
        insertActivePersonalRule("learned-rule", "生", "声")
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment("这是生记应用", sequence = 0),
            PROVENANCE,
        )
        repository.finalizeModelTranscript(
            RECORDING_ID,
            PROVENANCE,
            expectedSequences = listOf(0),
            now = 60L,
        )

        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        assertEquals(1, snapshot.candidates.size)
        assertTrue(
            personalCorrectionRepository.applyApproved(
                snapshot = snapshot,
                approved = snapshot.candidates,
                now = 61L,
            ),
        )

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("这是生记应用", recording.rawTranscript)
        assertEquals("这是声记应用", recording.correctedTranscript)
        assertEquals(1L, recording.correctionRevision)
        val segment = repository.getSegments(RECORDING_ID).single()
        assertEquals("这是生记应用", segment.rawText)
        assertEquals("这是声记应用", segment.correctedText)
        assertFalse(segment.isEdited)
        assertEquals(
            TranscriptRevisionSource.PERSONALIZED_LLM,
            repository.getRevisions(RECORDING_ID).last().source,
        )
        val audit = repository.getAuditRecords(RECORDING_ID).last()
        assertEquals("learned-rule", audit.sourceRuleId)
        assertEquals(CorrectionAuditReason.PERSONALIZED_LLM_CONTEXT_APPLIED, audit.reason)
    }

    @Test
    fun deterministicRulesKeepPriorityWhilePersonalCorrectionUsesOtherTextInTheSegment() =
        runBlocking {
            insertActivePersonalRule("learned-rule", "生", "声")
            insertActivePersonalRule("protected-rule", "Alpha版本", "恶意替换")
            insertRecording()
            repository.createGlobalLexiconRule(
                observedText = "阿尔法",
                replacementText = "Alpha版本",
                now = 60L,
            )
            repository.cacheModelSegment(
                RECORDING_ID,
                modelSegment("阿尔法 生记应用", sequence = 0),
                PROVENANCE,
            )
            repository.finalizeModelTranscript(
                RECORDING_ID,
                PROVENANCE,
                expectedSequences = listOf(0),
                applyGlobalLexicon = true,
                now = 61L,
            )

            val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)

            assertEquals(1, snapshot.candidates.size)
            val candidate = snapshot.candidates.single()
            assertEquals("learned-rule", candidate.ruleId)
            assertEquals(8, candidate.startCodePoint)
            assertEquals(4, candidate.rawStartCodePoint)
            assertTrue(
                personalCorrectionRepository.applyApproved(
                    snapshot = snapshot,
                    approved = snapshot.candidates,
                    now = 62L,
                ),
            )
            val recording = database.recordingDao().getById(RECORDING_ID)!!
            assertEquals("阿尔法 生记应用", recording.rawTranscript)
            assertEquals("Alpha版本 声记应用", recording.correctedTranscript)
            val personalAudit = repository.getAuditRecords(RECORDING_ID)
                .single {
                    it.reason == CorrectionAuditReason.PERSONALIZED_LLM_CONTEXT_APPLIED
                }
            assertEquals(4, personalAudit.rawStartCodePoint)
            assertEquals("生", personalAudit.originalText)
        }

    @Test
    fun stalePersonalCorrectionSnapshotFailsClosedWithoutAnotherRevision() = runBlocking {
        insertActivePersonalRule("learned-rule", "生", "声")
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        repository.editSegment(RECORDING_ID, segmentId, "用户的新版本", now = 62L)

        assertFalse(
            personalCorrectionRepository.applyApproved(
                snapshot,
                snapshot.candidates,
                now = 63L,
            ),
        )
        assertEquals("用户的新版本", repository.getSegments(RECORDING_ID).single().correctedText)
        assertEquals(1L, database.recordingDao().getById(RECORDING_ID)!!.correctionRevision)
    }

    @Test
    fun editingAnAutomaticPersonalCorrectionDisablesTheRuleAsNegativeFeedback() = runBlocking {
        insertActivePersonalRule("learned-rule", "生", "声")
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        assertTrue(
            personalCorrectionRepository.applyApproved(
                snapshot,
                snapshot.candidates,
                now = 64L,
            ),
        )

        repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "这是生记应用",
            capturePersonalLearning = false,
            now = 65L,
        )

        val oldRule = database.correctionDao().getRule("learned-rule")!!
        val oldProfile = database.personalCorrectionDao().getProfile("learned-rule")!!
        assertFalse(oldRule.isEnabled)
        assertEquals("DISABLED", oldProfile.state)
        assertEquals(1, oldProfile.negativeEvidenceCount)
        assertTrue(database.personalCorrectionDao().getActiveRules().isEmpty())
    }

    @Test
    fun correctingAnAutomaticResultLearnsFromTheOriginalAsrText() = runBlocking {
        insertActivePersonalRule("old-rule", "生", "声")
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        assertTrue(
            personalCorrectionRepository.applyApproved(
                snapshot,
                snapshot.candidates,
                now = 65L,
            ),
        )

        repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "这是升记应用",
            capturePersonalLearning = true,
            now = 66L,
        )

        assertFalse(database.correctionDao().getRule("old-rule")!!.isEnabled)
        val pending = personalCorrectionRepository.getPendingObservations(3).single()
        assertEquals("生", pending.observedText)
        assertEquals("升", pending.replacementText)
        assertEquals("这是", pending.leftContext)
        assertEquals("记应用", pending.rightContext)
    }

    @Test
    fun editingALargerRangeThatOverlapsAnAutomaticResultStillDisablesItsRule() = runBlocking {
        insertActivePersonalRule("learned-rule", "生", "声")
        val segmentId = insertFinalizedSingleSegment("这是生记应用")
        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        assertTrue(
            personalCorrectionRepository.applyApproved(
                snapshot,
                snapshot.candidates,
                now = 67L,
            ),
        )

        repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "这是升级软件",
            capturePersonalLearning = false,
            now = 68L,
        )

        assertFalse(database.correctionDao().getRule("learned-rule")!!.isEnabled)
        assertEquals(
            1,
            database.personalCorrectionDao()
                .getProfile("learned-rule")!!
                .negativeEvidenceCount,
        )
    }

    @Test
    fun negativeFeedbackFindsTheAppliedRuleAfterAnotherSegmentWasEdited() = runBlocking {
        insertActivePersonalRule("learned-rule", "生", "声")
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment("这是生记应用", sequence = 0),
            PROVENANCE,
        )
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment("第二段原文", sequence = 1),
            PROVENANCE,
        )
        repository.finalizeModelTranscript(
            RECORDING_ID,
            PROVENANCE,
            expectedSequences = listOf(0, 1),
            now = 66L,
        )
        val segments = repository.getSegments(RECORDING_ID)
        val firstSegment = segments.single { it.sequence == 0 }
        val secondSegment = segments.single { it.sequence == 1 }
        val snapshot = personalCorrectionRepository.prepareSnapshot(RECORDING_ID)
        assertTrue(
            personalCorrectionRepository.applyApproved(
                snapshot,
                snapshot.candidates,
                now = 67L,
            ),
        )
        repository.editSegment(
            RECORDING_ID,
            secondSegment.id,
            "第二段新版本",
            capturePersonalLearning = false,
            now = 68L,
        )

        repository.editSegment(
            RECORDING_ID,
            firstSegment.id,
            "这是生记应用",
            capturePersonalLearning = false,
            now = 69L,
        )

        assertFalse(database.correctionDao().getRule("learned-rule")!!.isEnabled)
        val profile = database.personalCorrectionDao().getProfile("learned-rule")!!
        assertEquals("DISABLED", profile.state)
        assertEquals(1, profile.negativeEvidenceCount)
    }

    @Test
    fun revertToRaw_createsExplicitRevisionAndAuditWithoutChangingRaw() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        repository.editSegment(RECORDING_ID, segmentId, "omega", now = 50L)

        repository.revertToRaw(RECORDING_ID, now = 51L)

        val stored = repository.getSegments(RECORDING_ID).single()
        assertEquals("alpha", stored.rawText)
        assertEquals("alpha", stored.correctedText)
        assertFalse(stored.isEdited)
        assertEquals(2L, stored.correctionRevision)
        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("alpha", recording.rawTranscript)
        assertEquals("alpha", recording.correctedTranscript)
        assertEquals(2L, recording.correctionRevision)
        assertFalse(recording.transcriptDirty)
        assertEquals(
            TranscriptRevisionSource.REVERT_TO_RAW,
            repository.getRevisions(RECORDING_ID).last().source
        )
        assertEquals(
            CorrectionAuditReason.REVERT_TO_RAW,
            repository.getAuditRecords(RECORDING_ID).last().reason
        )
    }

    @Test
    fun observeQueries_emitCurrentSegmentsRulesRevisionsAndAudit() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val diff = repository.editSegment(RECORDING_ID, segmentId, "omega", now = 60L)!!
        repository.rememberRecordingRule(RECORDING_ID, diff, now = 61L)

        assertEquals(1, repository.observeSegments(RECORDING_ID).first().size)
        assertEquals(1, repository.observeRecordingRules(RECORDING_ID).first().size)
        assertEquals(2, repository.observeRevisions(RECORDING_ID).first().size)
        assertEquals(1, repository.observeAuditRecords(RECORDING_ID).first().size)
    }

    @Test
    fun persistedUnknownRuleTypeIsNotExposedThroughRecordingApi() = runBlocking {
        insertRecording()
        database.correctionDao().insertRule(
            CorrectionRuleEntity(
                id = "unknown-rule",
                observedText = "a",
                replacementText = "b",
                scope = "FUTURE_SCOPE",
                scopeRecordingId = null,
                matchMode = "FUTURE_MODE",
                createdAt = 70L,
                updatedAt = 70L
            )
        )

        assertTrue(repository.getRecordingRules(RECORDING_ID).isEmpty())
    }

    @Test
    fun editSegment_allowsExplicitEmptyTextAndPreservesRaw() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")

        val diff = repository.editSegment(RECORDING_ID, segmentId, "", now = 80L)

        assertNotNull(diff)
        assertFalse(diff!!.eligibleForRule)
        val segment = repository.getSegments(RECORDING_ID).single()
        assertEquals("alpha", segment.rawText)
        assertEquals("", segment.correctedText)
        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("alpha", recording.rawTranscript)
        assertEquals("", recording.correctedTranscript)
        assertEquals(1L, recording.correctionRevision)
    }

    @Test
    fun editSegment_sameTextIsANoOp() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")

        assertNull(repository.editSegment(RECORDING_ID, segmentId, "alpha", now = 81L))

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals(0L, recording.correctionRevision)
        assertFalse(recording.transcriptDirty)
        assertFalse(repository.getSegments(RECORDING_ID).single().isEdited)
        assertEquals(1, repository.getRevisions(RECORDING_ID).size)
        assertTrue(repository.getAuditRecords(RECORDING_ID).isEmpty())
    }

    @Test
    fun rememberRecordingRule_isIdempotentAndRejectsConflictingOrReverseRules() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val diff = repository.editSegment(RECORDING_ID, segmentId, "omega", now = 82L)!!

        val first = repository.rememberRecordingRule(RECORDING_ID, diff, now = 83L)
        val duplicate = repository.rememberRecordingRule(RECORDING_ID, diff, now = 84L)

        assertEquals(first.id, duplicate.id)
        assertEquals(1, repository.getRecordingRules(RECORDING_ID).size)
        assertThrows(CorrectionRuleConflictException::class.java) {
            runBlocking {
                repository.rememberRecordingRule(
                    RECORDING_ID,
                    diff.copy(replacementText = "different"),
                    now = 85L
                )
            }
        }
        assertThrows(CorrectionRuleConflictException::class.java) {
            runBlocking {
                repository.rememberRecordingRule(
                    RECORDING_ID,
                    app.murmurnote.android.domain.correction.SingleReplacementDiff(
                        startCodePoint = 0,
                        endCodePointExclusive = 5,
                        observedText = "omega",
                        replacementText = "alpha",
                        eligibleForRule = true
                    ),
                    now = 86L
                )
            }
        }
        repository.setRecordingRuleEnabled(RECORDING_ID, first.id, enabled = false, now = 87L)
        assertFalse(repository.getRecordingRules(RECORDING_ID).single().isEnabled)
    }

    @Test
    fun recordingRuleApiNeverReusesOrConflictsWithGlobalLexiconRows() = runBlocking {
        insertRecording()
        val global = repository.createGlobalLexiconRule("alpha", "omega", now = 90L)
        val recording = repository.rememberRecordingRule(
            recordingId = RECORDING_ID,
            diff = app.murmurnote.android.domain.correction.SingleReplacementDiff(
                startCodePoint = 0,
                endCodePointExclusive = 5,
                observedText = "alpha",
                replacementText = "omega",
                eligibleForRule = true,
            ),
            now = 91L,
        )

        assertEquals(CorrectionScope.GLOBAL, global.scope)
        assertEquals(CorrectionScope.RECORDING, recording.scope)
        assertFalse(global.id == recording.id)
        assertEquals(listOf(recording.id), repository.getRecordingRules(RECORDING_ID).map { it.id })
        assertEquals(listOf(global.id), repository.observeGlobalLexiconRules().first().map { it.id })
    }

    @Test
    fun recordingRuleToggleCannotMutateAGlobalLexiconRow() = runBlocking {
        insertRecording()
        val global = repository.createGlobalLexiconRule("alpha", "omega", now = 92L)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.setRecordingRuleEnabled(
                    recordingId = RECORDING_ID,
                    ruleId = global.id,
                    enabled = false,
                    now = 93L,
                )
            }
        }

        assertTrue(repository.observeGlobalLexiconRules().first().single().isEnabled)
        assertTrue(repository.getRecordingRules(RECORDING_ID).isEmpty())
    }

    @Test
    fun globalLexicon_createReactivateToggleAndDeleteAreScopedAndIdempotent() = runBlocking {
        insertRecording()

        val created = repository.createGlobalLexiconRule(
            observedText = "  木木笔记  ",
            replacementText = "  声记应用  ",
            now = 100L,
        )
        val duplicate = repository.createGlobalLexiconRule(
            observedText = "木木笔记",
            replacementText = "声记应用",
            now = 101L,
        )

        assertEquals(created.id, duplicate.id)
        assertEquals("木木笔记", created.observedText)
        assertEquals("声记应用", created.replacementText)
        assertEquals(CorrectionScope.GLOBAL, created.scope)
        assertEquals(1, repository.observeGlobalLexiconRules().first().size)

        repository.setGlobalLexiconRuleEnabled(created.id, enabled = false, now = 102L)
        assertFalse(repository.observeGlobalLexiconRules().first().single().isEnabled)

        val reactivated = repository.createGlobalLexiconRule(
            observedText = "木木笔记",
            replacementText = "声记应用",
            now = 103L,
        )
        assertEquals(created.id, reactivated.id)
        assertTrue(reactivated.isEnabled)

        repository.deleteGlobalLexiconRule(created.id)
        assertTrue(repository.observeGlobalLexiconRules().first().isEmpty())
    }

    @Test
    fun globalLexicon_rejectsConflictsAndCannotMutateRecordingRules() = runBlocking {
        insertRecording()
        repository.createGlobalLexiconRule("木木笔记", "声记应用", now = 110L)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createGlobalLexiconRule("木木笔记", "其他写法", now = 111L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.createGlobalLexiconRule("声记应用", "木木笔记", now = 112L)
            }
        }

        val recordingRule = repository.rememberRecordingRule(
            recordingId = RECORDING_ID,
            diff = app.murmurnote.android.domain.correction.SingleReplacementDiff(
                startCodePoint = 0,
                endCodePointExclusive = 5,
                observedText = "alpha",
                replacementText = "omega",
                eligibleForRule = true,
            ),
            now = 113L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.setGlobalLexiconRuleEnabled(
                    recordingRule.id,
                    enabled = false,
                    now = 114L,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.deleteGlobalLexiconRule(recordingRule.id) }
        }
        assertTrue(
            repository.getRecordingRules(RECORDING_ID)
                .single { it.id == recordingRule.id }
                .isEnabled,
        )
    }

    @Test
    fun globalLexicon_rejectsReenablingALegacyConflict() = runBlocking {
        database.correctionDao().insertRule(
            CorrectionRuleEntity(
                id = "disabled-rule",
                observedText = "木木笔记",
                replacementText = "声记应用",
                scope = CorrectionScope.GLOBAL.name,
                scopeRecordingId = null,
                isEnabled = false,
                createdAt = 120L,
                updatedAt = 120L,
            ),
        )
        database.correctionDao().insertRule(
            CorrectionRuleEntity(
                id = "enabled-conflict",
                observedText = "木木笔记",
                replacementText = "其他写法",
                scope = CorrectionScope.GLOBAL.name,
                scopeRecordingId = null,
                isEnabled = true,
                createdAt = 121L,
                updatedAt = 121L,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.setGlobalLexiconRuleEnabled(
                    ruleId = "disabled-rule",
                    enabled = true,
                    now = 122L,
                )
            }
        }

        val target = repository.observeGlobalLexiconRules().first()
            .single { it.id == "disabled-rule" }
        assertFalse(target.isEnabled)
    }

    @Test
    fun deletingAUsedGlobalLexiconRuleKeepsTheHistoricalTextSnapshots() = runBlocking {
        insertRecording()
        repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = "alpha", sequence = 0),
            PROVENANCE,
        )
        val rule = repository.createGlobalLexiconRule("alpha", "omega", now = 130L)
        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0),
            applyGlobalLexicon = true,
            now = 131L,
        )

        repository.deleteGlobalLexiconRule(rule.id)

        val audit = repository.getAuditRecords(RECORDING_ID).single()
        assertNull(audit.sourceRuleId)
        assertEquals("alpha", audit.originalText)
        assertEquals("omega", audit.replacementText)
        assertEquals("alpha", repository.getSegments(RECORDING_ID).single().rawText)
        assertEquals("omega", repository.getSegments(RECORDING_ID).single().correctedText)
    }

    @Test
    fun summarySave_isAtomicAndBoundToExpectedTranscriptRevision() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val initialItems = listOf(item("revision zero"))

        assertTrue(
            summaryRepository.saveForRevision(
                recordingId = RECORDING_ID,
                expectedRevision = 0,
                title = "Revision zero",
                summary = "summary zero",
                items = initialItems
            )
        )
        repository.editSegment(RECORDING_ID, segmentId, "omega", now = 90L)

        assertFalse(
            summaryRepository.saveForRevision(
                recordingId = RECORDING_ID,
                expectedRevision = 0,
                title = "stale",
                summary = "stale summary",
                items = listOf(item("stale item"))
            )
        )

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("summary zero", recording.finalSummary)
        assertEquals(0L, recording.summaryTranscriptRevision)
        assertTrue(recording.transcriptDirty)
        assertEquals(
            listOf("revision zero"),
            database.itemDao().observeForRecording(RECORDING_ID).first().map { it.content }
        )
    }

    private suspend fun insertFinalizedSingleSegment(rawText: String): Long {
        insertRecording()
        val segment = repository.cacheModelSegment(
            RECORDING_ID,
            modelSegment(rawText = rawText, sequence = 0),
            PROVENANCE
        )
        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0),
            now = 1L
        )
        return segment.id
    }

    private suspend fun insertActivePersonalRule(
        id: String,
        observedText: String,
        replacementText: String,
    ) {
        database.correctionDao().insertRule(
            CorrectionRuleEntity(
                id = id,
                observedText = observedText,
                replacementText = replacementText,
                scope = CorrectionScope.GLOBAL.name,
                matchMode = "CONTEXTUAL_LLM",
                isEnabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.personalCorrectionDao().insertProfile(
            app.murmurnote.android.data.local.entity.CorrectionLearningProfileEntity(
                ruleId = id,
                state = "ACTIVE",
                positiveEvidenceCount = 1,
                negativeEvidenceCount = 0,
                observedPinyin = "sheng",
                replacementPinyin = "sheng",
                pinyinRelation = "EXACT_PINYIN",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun insertRecording() {
        database.recordingDao().insert(
            Recording(
                id = RECORDING_ID,
                title = "Repository test",
                originalFilePath = "/test.wav",
                durationMs = 1_000,
                createdAt = 0,
                source = RecordingSource.RECORDED,
                processingStatus = ProcessingStatus.TRANSCRIBING
            )
        )
    }

    private fun modelSegment(
        rawText: String,
        sequence: Int,
        startMs: Long = sequence * 500L,
        endMs: Long = startMs + 500L
    ) = ModelTranscriptSegment(
        rawText = rawText,
        startMs = startMs,
        endMs = endMs,
        sequence = sequence
    )

    private fun item(content: String) = ExtractedItem(
        recordingId = RECORDING_ID,
        type = ItemType.NOTE,
        content = content,
        createdAt = 0L
    )

    private companion object {
        const val RECORDING_ID = "recording-1"
        val PROVENANCE = AsrProvenance(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            modelId = "sense-voice-test",
            configFingerprint = "fingerprint-1",
            configSnapshotJson = "{\"frozen\":true}",
            vadPresetVersion = "vad-preset-1"
        )
    }
}
