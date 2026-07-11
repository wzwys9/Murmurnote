package app.murmurnote.android.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MurmurnoteDatabase::class.java
    )

    @Test
    fun migrate5To6_preservesEveryLegacyTextByteAndArchivesDuplicateSequences() {
        val uneditedTranscript = "未编辑\u0000原文\n\"quoted\"🙂"
        val editedTranscript = "已编辑\u0000legacy\n'quoted'𠮷"
        val uneditedSegment = "段一\u0000\n\"A\"🙂"
        val duplicateOlder = "旧重复\u0000\n'B'𠮷"
        val duplicateWinner = "新重复\u0000\r\n\"C\"🫠"
        val editedUniqueSegment = "独立编辑段\u0000\nD"
        val expirationDate = 1_725_000_123_456L

        helper.createDatabase(TEST_DB, 5).apply {
            insertRecording(
                id = UNEDITED_RECORDING_ID,
                rawTranscript = uneditedTranscript,
                transcriptDirty = false,
                transcriptEditedAt = null,
                summary = null,
                finalSummary = null,
                expirationDate = expirationDate
            )
            insertRecording(
                id = EDITED_RECORDING_ID,
                rawTranscript = editedTranscript,
                transcriptDirty = true,
                transcriptEditedAt = 1_725_000_000_111L,
                summary = null,
                finalSummary = "final summary",
                expirationDate = null
            )
            insertRecording(
                id = SUMMARY_ONLY_RECORDING_ID,
                rawTranscript = null,
                transcriptDirty = false,
                transcriptEditedAt = null,
                summary = "legacy summary without transcript",
                finalSummary = null,
                expirationDate = null
            )

            insertTranscriptSegment(
                id = 101L,
                recordingId = UNEDITED_RECORDING_ID,
                sequence = 0,
                text = uneditedSegment,
                startMs = 0L,
                endMs = 900L,
                isEdited = false,
                editedAt = null
            )
            insertTranscriptSegment(
                id = 201L,
                recordingId = EDITED_RECORDING_ID,
                sequence = 0,
                text = duplicateOlder,
                startMs = 10L,
                endMs = 510L,
                isEdited = false,
                editedAt = null
            )
            insertTranscriptSegment(
                id = 203L,
                recordingId = EDITED_RECORDING_ID,
                sequence = 0,
                text = duplicateWinner,
                startMs = 20L,
                endMs = 620L,
                isEdited = true,
                editedAt = 1_725_000_000_222L
            )
            insertTranscriptSegment(
                id = 202L,
                recordingId = EDITED_RECORDING_ID,
                sequence = 1,
                text = editedUniqueSegment,
                startMs = 620L,
                endMs = 1_120L,
                isEdited = true,
                editedAt = 1_725_000_000_333L
            )
            insert(
                "api_logs",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", 301L)
                    put("timestamp", 1_725_000_000_444L)
                    put("apiName", "legacy")
                    put("method", "POST")
                    put("url", "https://example.invalid/asr?transcript=private")
                    put("requestBody", "private request transcript")
                    put("responseCode", 200)
                    put("responseBody", "private response summary")
                    put("durationMs", 10L)
                    put("errorMessage", "private upstream response")
                }
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            MurmurnoteDatabase.MIGRATION_5_6
        )

        db.query(
            """
            SELECT id, rawTranscript, correctedTranscript, correctionRevision,
                   summaryTranscriptRevision, rawProvenance, expirationDate,
                   audioExpiresAt, keepAudio, audioAvailable, asrEngineType,
                   asrModelId, asrConfigFingerprint, asrConfigSnapshotJson,
                   vadPresetVersion
            FROM recordings
            ORDER BY id DESC
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EDITED_RECORDING_ID, cursor.string("id"))
            assertTextBytesEqual(editedTranscript, cursor.string("rawTranscript"))
            assertTextBytesEqual(editedTranscript, cursor.string("correctedTranscript"))
            assertEquals(0L, cursor.long("correctionRevision"))
            assertEquals(0L, cursor.long("summaryTranscriptRevision"))
            assertEquals(LEGACY_PROVENANCE_UNKNOWN, cursor.string("rawProvenance"))
            assertNull(cursor.nullableLong("expirationDate"))
            assertNull(cursor.nullableLong("audioExpiresAt"))
            assertEquals(0, cursor.int("keepAudio"))
            assertEquals(1, cursor.int("audioAvailable"))
            assertNull(cursor.nullableString("asrEngineType"))
            assertNull(cursor.nullableString("asrModelId"))
            assertNull(cursor.nullableString("asrConfigFingerprint"))
            assertNull(cursor.nullableString("asrConfigSnapshotJson"))
            assertNull(cursor.nullableString("vadPresetVersion"))

            assertTrue(cursor.moveToNext())
            assertEquals(UNEDITED_RECORDING_ID, cursor.string("id"))
            assertTextBytesEqual(uneditedTranscript, cursor.string("rawTranscript"))
            assertTextBytesEqual(uneditedTranscript, cursor.string("correctedTranscript"))
            assertEquals(0L, cursor.long("correctionRevision"))
            assertNull(cursor.nullableLong("summaryTranscriptRevision"))
            assertEquals(LEGACY_PROVENANCE_UNKNOWN, cursor.string("rawProvenance"))
            assertNull(cursor.nullableLong("expirationDate"))
            assertEquals(expirationDate, cursor.long("audioExpiresAt"))
            assertEquals(0, cursor.int("keepAudio"))
            assertEquals(1, cursor.int("audioAvailable"))

            assertTrue(cursor.moveToNext())
            assertEquals(SUMMARY_ONLY_RECORDING_ID, cursor.string("id"))
            assertNull(cursor.nullableString("rawTranscript"))
            assertNull(cursor.nullableString("correctedTranscript"))
            assertEquals(0L, cursor.long("correctionRevision"))
            assertNull(cursor.nullableLong("summaryTranscriptRevision"))
            assertEquals(MODEL_OUTPUT, cursor.string("rawProvenance"))
            assertFalse(cursor.moveToNext())
        }

        val activeByLegacyId = linkedMapOf<Long, String>()
        db.query(
            """
            SELECT id, recordingId, sequence, rawText, correctedText, rawProvenance,
                   correctionRevision, isEdited, editedAt, vadPresetVersion, cutReason,
                   overlapBeforeMs
            FROM transcript_segments
            ORDER BY id
            """.trimIndent()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.long("id")
                val rawText = cursor.string("rawText")
                activeByLegacyId[id] = rawText
                assertTextBytesEqual(rawText, cursor.string("correctedText"))
                assertEquals(LEGACY_PROVENANCE_UNKNOWN, cursor.string("rawProvenance"))
                assertEquals(0L, cursor.long("correctionRevision"))
                assertNull(cursor.nullableString("vadPresetVersion"))
                assertNull(cursor.nullableString("cutReason"))
                assertEquals(0L, cursor.long("overlapBeforeMs"))
            }
        }
        assertEquals(setOf(101L, 202L, 203L), activeByLegacyId.keys)
        assertTextBytesEqual(uneditedSegment, activeByLegacyId.getValue(101L))
        assertTextBytesEqual(editedUniqueSegment, activeByLegacyId.getValue(202L))
        assertTextBytesEqual(duplicateWinner, activeByLegacyId.getValue(203L))

        val archivedByLegacyId = linkedMapOf<Long, String>()
        db.query(
            """
            SELECT recordingId, legacySegmentId, sequence, text, startMs, endMs,
                   isEdited, editedAt, reason
            FROM legacy_transcript_segment_conflicts
            ORDER BY legacySegmentId
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EDITED_RECORDING_ID, cursor.string("recordingId"))
            assertEquals(201L, cursor.long("legacySegmentId"))
            assertEquals(0, cursor.int("sequence"))
            assertTextBytesEqual(duplicateOlder, cursor.string("text"))
            assertEquals(10L, cursor.long("startMs"))
            assertEquals(510L, cursor.long("endMs"))
            assertEquals(0, cursor.int("isEdited"))
            assertNull(cursor.nullableLong("editedAt"))
            assertEquals(DUPLICATE_SEQUENCE_REASON, cursor.string("reason"))
            archivedByLegacyId[cursor.long("legacySegmentId")] = cursor.string("text")
            assertFalse(cursor.moveToNext())
        }

        val allMigratedTexts = activeByLegacyId + archivedByLegacyId
        val allLegacyTexts = mapOf(
            101L to uneditedSegment,
            201L to duplicateOlder,
            202L to editedUniqueSegment,
            203L to duplicateWinner
        )
        assertEquals(allLegacyTexts.keys, allMigratedTexts.keys)
        allLegacyTexts.forEach { (legacyId, legacyText) ->
            assertTextBytesEqual(legacyText, allMigratedTexts.getValue(legacyId))
        }

        db.query(
            "SELECT recordingId, revision, text, source FROM transcript_revisions ORDER BY recordingId DESC"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(EDITED_RECORDING_ID, cursor.string("recordingId"))
            assertEquals(0L, cursor.long("revision"))
            assertTextBytesEqual(editedTranscript, cursor.string("text"))
            assertEquals("LEGACY_MIGRATION", cursor.string("source"))
            assertTrue(cursor.moveToNext())
            assertEquals(UNEDITED_RECORDING_ID, cursor.string("recordingId"))
            assertEquals(0L, cursor.long("revision"))
            assertTextBytesEqual(uneditedTranscript, cursor.string("text"))
            assertEquals("LEGACY_MIGRATION", cursor.string("source"))
            assertFalse(cursor.moveToNext())
        }

        db.query(
            "SELECT url, requestBody, responseBody, errorMessage FROM api_logs WHERE id = 301"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("<redacted>", cursor.string("url"))
            assertNull(cursor.nullableString("requestBody"))
            assertNull(cursor.nullableString("responseBody"))
            assertNull(cursor.nullableString("errorMessage"))
            assertFalse(cursor.moveToNext())
        }

        assertEquals(
            setOf(
                "transcript_revisions",
                "correction_rules",
                "correction_records",
                "legacy_transcript_segment_conflicts"
            ),
            db.userTableNames().intersect(
                setOf(
                    "transcript_revisions",
                    "correction_rules",
                    "correction_records",
                    "legacy_transcript_segment_conflicts"
                )
            )
        )
        assertUniqueIndex(
            db,
            table = "transcript_segments",
            indexName = "index_transcript_segments_recordingId_sequence"
        )
        assertUniqueIndex(
            db,
            table = "transcript_revisions",
            indexName = "index_transcript_revisions_recordingId_revision"
        )
        assertUniqueIndex(
            db,
            table = "legacy_transcript_segment_conflicts",
            indexName = "index_legacy_transcript_segment_conflicts_legacySegmentId"
        )
    }

    private fun SupportSQLiteDatabase.insertRecording(
        id: String,
        rawTranscript: String?,
        transcriptDirty: Boolean,
        transcriptEditedAt: Long?,
        summary: String?,
        finalSummary: String?,
        expirationDate: Long?
    ) {
        insert(
            "recordings",
            SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("title", "title-$id")
                put("originalFilePath", "/audio/$id.wav")
                put("durationMs", 1_120L)
                put("createdAt", 1_725_000_000_000L)
                put("source", "RECORDED")
                put("processingStatus", "COMPLETED")
                putNull("errorMessage")
                if (summary == null) putNull("summary") else put("summary", summary)
                putNull("draftSummary")
                if (finalSummary == null) putNull("finalSummary") else put("finalSummary", finalSummary)
                put("transcriptDirty", transcriptDirty)
                if (transcriptEditedAt == null) putNull("transcriptEditedAt") else put("transcriptEditedAt", transcriptEditedAt)
                if (rawTranscript == null) putNull("rawTranscript") else put("rawTranscript", rawTranscript)
                put("tags", "")
                put("archived", false)
                if (expirationDate == null) putNull("expirationDate") else put("expirationDate", expirationDate)
            }
        )
    }

    private fun SupportSQLiteDatabase.insertTranscriptSegment(
        id: Long,
        recordingId: String,
        sequence: Int,
        text: String,
        startMs: Long,
        endMs: Long,
        isEdited: Boolean,
        editedAt: Long?
    ) {
        insert(
            "transcript_segments",
            SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("recordingId", recordingId)
                put("text", text)
                put("startMs", startMs)
                put("endMs", endMs)
                put("sequence", sequence)
                put("isEdited", isEdited)
                if (editedAt == null) putNull("editedAt") else put("editedAt", editedAt)
            }
        )
    }

    private fun assertUniqueIndex(
        db: SupportSQLiteDatabase,
        table: String,
        indexName: String
    ) {
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            var foundUnique = false
            while (cursor.moveToNext()) {
                if (cursor.string("name") == indexName) {
                    foundUnique = cursor.int("unique") == 1
                }
            }
            assertTrue("Missing unique index $indexName on $table", foundUnique)
        }
    }

    private fun SupportSQLiteDatabase.userTableNames(): Set<String> {
        return query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private fun assertTextBytesEqual(expected: String, actual: String) {
        assertEquals(expected, actual)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String): String = getString(column(name))
    private fun Cursor.nullableString(name: String): String? =
        column(name).let { index -> if (isNull(index)) null else getString(index) }
    private fun Cursor.long(name: String): Long = getLong(column(name))
    private fun Cursor.nullableLong(name: String): Long? =
        column(name).let { index -> if (isNull(index)) null else getLong(index) }
    private fun Cursor.int(name: String): Int = getInt(column(name))

    companion object {
        private const val TEST_DB = "migration-5-to-6"
        private const val UNEDITED_RECORDING_ID = "recording-a-unedited"
        private const val EDITED_RECORDING_ID = "recording-b-edited"
        private const val SUMMARY_ONLY_RECORDING_ID = "recording-0-summary-only"
        private const val LEGACY_PROVENANCE_UNKNOWN = "LEGACY_PROVENANCE_UNKNOWN"
        private const val MODEL_OUTPUT = "MODEL_OUTPUT"
        private const val DUPLICATE_SEQUENCE_REASON = "DUPLICATE_RECORDING_SEQUENCE"
    }
}
