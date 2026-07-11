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
            correctionDao = database.correctionDao()
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
                    cutReason = ModelSegmentCutReason.HARD_LIMIT,
                    overlapBeforeMs = 500
                ),
            PROVENANCE
        )
        repository.rememberRule(
            recordingId = RECORDING_ID,
            diff = app.murmurnote.android.domain.correction.SingleReplacementDiff(
                startCodePoint = 0,
                endCodePointExclusive = 5,
                observedText = "alpha",
                replacementText = "A",
                eligibleForRule = true
            ),
            scope = CorrectionScope.GLOBAL,
            now = 10L
        )

        repository.finalizeModelTranscript(
            recordingId = RECORDING_ID,
            provenance = PROVENANCE,
            expectedSequences = listOf(0, 1),
            now = 20L
        )

        val recording = database.recordingDao().getById(RECORDING_ID)!!
        assertEquals("alpha\nbeta alpha", recording.rawTranscript)
        assertEquals("A\nbeta A", recording.correctedTranscript)
        assertEquals(0L, recording.correctionRevision)
        assertEquals(PROVENANCE.engineType.name, recording.asrEngineType)
        assertEquals(PROVENANCE.modelId, recording.asrModelId)
        assertEquals(PROVENANCE.configFingerprint, recording.asrConfigFingerprint)
        assertEquals(PROVENANCE.configSnapshotJson, recording.asrConfigSnapshotJson)
        assertEquals(PROVENANCE.vadPresetVersion, recording.vadPresetVersion)
        assertEquals(listOf("A", "beta A"), repository.getSegments(RECORDING_ID).map { it.correctedText })
        val hardCut = repository.getSegments(RECORDING_ID).last()
        assertEquals(ModelSegmentCutReason.HARD_LIMIT.name, hardCut.cutReason)
        assertEquals(500L, hardCut.overlapBeforeMs)
        assertEquals(PROVENANCE.vadPresetVersion, hardCut.vadPresetVersion)

        val revisions = repository.getRevisions(RECORDING_ID)
        assertEquals(1, revisions.size)
        assertEquals(TranscriptRevisionSource.MODEL_FINAL, revisions.single().source)
        assertEquals("A\nbeta A", revisions.single().text)
        val audit = repository.getAuditRecords(RECORDING_ID)
        assertEquals(listOf(0, 11), audit.map { it.rawStartCodePoint })
        assertTrue(audit.all { it.sourceRuleId != null })
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
        assertTrue(recording.transcriptDirty)
        assertEquals(30L, recording.transcriptEditedAt)

        val revisions = repository.getRevisions(RECORDING_ID)
        assertEquals(listOf(0L, 1L), revisions.map { it.revision })
        assertEquals(listOf("alpha", newText), revisions.map { it.text })
        assertEquals(
            listOf(TranscriptRevisionSource.MODEL_FINAL, TranscriptRevisionSource.MANUAL_EDIT),
            revisions.map { it.source }
        )
        assertTrue(repository.getRules(RECORDING_ID).isEmpty())
        val manualAudit = repository.getAuditRecords(RECORDING_ID).last()
        assertNull(manualAudit.sourceRuleId)
        assertEquals(CorrectionAuditReason.MANUAL_EDIT, manualAudit.reason)
    }

    @Test
    fun rememberRule_requiresExplicitCallAndDoesNotRewriteHistory() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val diff = repository.editSegment(
            recordingId = RECORDING_ID,
            segmentId = segmentId,
            newText = "omega",
            now = 40L
        )!!
        assertTrue(repository.getRules(RECORDING_ID).isEmpty())

        val rule = repository.rememberRule(
            recordingId = RECORDING_ID,
            diff = diff,
            scope = CorrectionScope.RECORDING,
            now = 41L
        )

        assertEquals(CorrectionScope.RECORDING, rule.scope)
        assertEquals(RECORDING_ID, rule.scopeId)
        assertEquals("omega", repository.getSegments(RECORDING_ID).single().correctedText)
        assertEquals(1, repository.getRules(RECORDING_ID).size)
        assertEquals(2, repository.getRevisions(RECORDING_ID).size)
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
        assertTrue(recording.transcriptDirty)
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
        repository.rememberRule(RECORDING_ID, diff, CorrectionScope.RECORDING, now = 61L)

        assertEquals(1, repository.observeSegments(RECORDING_ID).first().size)
        assertEquals(1, repository.observeRules(RECORDING_ID).first().size)
        assertEquals(2, repository.observeRevisions(RECORDING_ID).first().size)
        assertEquals(1, repository.observeAuditRecords(RECORDING_ID).first().size)
    }

    @Test
    fun persistedUnknownEnumFailsClosed() = runBlocking {
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

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.getRules(RECORDING_ID) }
        }
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
    fun rememberRule_isIdempotentAndRejectsConflictingOrReverseRules() = runBlocking {
        val segmentId = insertFinalizedSingleSegment("alpha")
        val diff = repository.editSegment(RECORDING_ID, segmentId, "omega", now = 82L)!!

        val first = repository.rememberRule(RECORDING_ID, diff, now = 83L)
        val duplicate = repository.rememberRule(RECORDING_ID, diff, now = 84L)

        assertEquals(first.id, duplicate.id)
        assertEquals(1, repository.getRules(RECORDING_ID).size)
        assertThrows(CorrectionRuleConflictException::class.java) {
            runBlocking {
                repository.rememberRule(
                    RECORDING_ID,
                    diff.copy(replacementText = "different"),
                    now = 85L
                )
            }
        }
        assertThrows(CorrectionRuleConflictException::class.java) {
            runBlocking {
                repository.rememberRule(
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
        repository.setRuleEnabled(RECORDING_ID, first.id, enabled = false, now = 87L)
        assertFalse(repository.getRules(RECORDING_ID).single().isEnabled)
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
