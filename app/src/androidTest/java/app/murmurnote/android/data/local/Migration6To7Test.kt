package app.murmurnote.android.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6To7Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MurmurnoteDatabase::class.java
    )

    @Test
    fun migrate6To7_preservesRecordingAndScrubsLegacyDiagnostics() {
        helper.createDatabase(TEST_DB, 6).apply {
            insert(
                "recordings",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", RECORDING_ID)
                    put("title", "升级前记录")
                    put("originalFilePath", "/audio/before-upgrade.wav")
                    put("durationMs", 1_234L)
                    put("createdAt", 1_725_000_000_000L)
                    put("source", "RECORDED")
                    put("processingStatus", "COMPLETED")
                    put("transcriptDirty", false)
                    put("rawTranscript", "升级前原文🙂")
                    put("tags", "migration")
                    put("archived", false)
                    put("correctedTranscript", "升级前修订🙂")
                    put("correctionRevision", 2L)
                    put("rawProvenance", "MODEL_OUTPUT")
                    put("keepAudio", true)
                    put("audioAvailable", true)
                }
            )
            insert(
                "api_logs",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", 99L)
                    put("timestamp", 1_725_000_000_100L)
                    put("apiName", "legacy")
                    put("method", "POST")
                    put("url", "https://example.invalid/asr?transcript=private")
                    put("requestBody", "private request transcript")
                    put("responseCode", 500)
                    put("responseBody", "private response body")
                    put("durationMs", 10L)
                    put("errorMessage", "private upstream error")
                }
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            MurmurnoteDatabase.MIGRATION_6_7
        )

        db.query(
            """
            SELECT id, title, rawTranscript, correctedTranscript, correctionRevision,
                   keepAudio, audioAvailable
            FROM recordings
            WHERE id = ?
            """.trimIndent(),
            arrayOf(RECORDING_ID)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(RECORDING_ID, cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals("升级前记录", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("升级前原文🙂", cursor.getString(cursor.getColumnIndexOrThrow("rawTranscript")))
            assertEquals(
                "升级前修订🙂",
                cursor.getString(cursor.getColumnIndexOrThrow("correctedTranscript"))
            )
            assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("correctionRevision")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("keepAudio")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("audioAvailable")))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT url, requestBody, responseBody, errorMessage FROM api_logs WHERE id = 99"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "<redacted>",
                cursor.getString(cursor.getColumnIndexOrThrow("url"))
            )
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("requestBody")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("responseBody")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("errorMessage")))
            assertFalse(cursor.moveToNext())
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-6-to-7"
        private const val RECORDING_ID = "recording-before-v7"
    }
}
