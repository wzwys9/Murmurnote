package app.murmurnote.android.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MurmurnoteDatabase::class.java,
    )

    @Test
    fun migrate7To8_preservesExactRulesAndAddsLearningTablesWithExpectedLifetime() {
        helper.createDatabase(TEST_DB, 7).apply {
            insert(
                "recordings",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", RECORDING_ID)
                    put("title", "迁移前录音")
                    put("originalFilePath", "/audio/before-v8.wav")
                    put("durationMs", 1_000L)
                    put("createdAt", 100L)
                    put("source", "RECORDED")
                    put("processingStatus", "COMPLETED")
                    put("transcriptDirty", false)
                    put("tags", "")
                    put("archived", false)
                    put("correctionRevision", 0L)
                    put("rawProvenance", "MODEL_OUTPUT")
                    put("keepAudio", false)
                    put("audioAvailable", true)
                },
            )
            insert(
                "correction_rules",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("id", EXACT_RULE_ID)
                    put("observedText", "木木笔记")
                    put("replacementText", "声记应用")
                    put("scope", "GLOBAL")
                    putNull("scopeRecordingId")
                    put("matchMode", "EXACT_TEXT")
                    put("isEnabled", true)
                    put("createdAt", 101L)
                    put("updatedAt", 101L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            MurmurnoteDatabase.MIGRATION_7_8,
        )
        // MigrationTestHelper returns a raw connection without Room's generated onConfigure hook.
        // Mirror production before verifying the migrated foreign-key cascade behavior.
        db.execSQL("PRAGMA foreign_keys = ON")

        db.query(
            "SELECT observedText, replacementText, matchMode FROM correction_rules WHERE id = ?",
            arrayOf(EXACT_RULE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("木木笔记", cursor.getString(0))
            assertEquals("声记应用", cursor.getString(1))
            assertEquals("EXACT_TEXT", cursor.getString(2))
            assertFalse(cursor.moveToNext())
        }

        db.execSQL(
            """
            INSERT INTO correction_rules (
                id, observedText, replacementText, scope, scopeRecordingId,
                matchMode, isEnabled, createdAt, updatedAt
            ) VALUES (?, ?, ?, 'GLOBAL', NULL, 'CONTEXTUAL_LLM', 1, 200, 200)
            """.trimIndent(),
            arrayOf(LEARNED_RULE_ID, "生", "声"),
        )
        db.execSQL(
            """
            INSERT INTO correction_learning_profiles (
                ruleId, state, positiveEvidenceCount, negativeEvidenceCount,
                observedPinyin, replacementPinyin, pinyinRelation,
                lastVerdict, lastConfidence, lastReasonCode,
                lastReviewedAt, createdAt, updatedAt
            ) VALUES (?, 'ACTIVE', 1, 0, 'sheng', 'sheng', 'EXACT_PINYIN',
                      'ACTIVATE', 'HIGH', 'PHONETIC_ASR_ERROR', 201, 200, 201)
            """.trimIndent(),
            arrayOf(LEARNED_RULE_ID),
        )
        db.execSQL(
            """
            INSERT INTO correction_learning_events (
                id, ruleId, recordingId, segmentId, revision,
                leftContext, rightContext, status, pinyinRelation,
                llmVerdict, llmConfidence, llmReasonCode,
                createdAt, reviewedAt
            ) VALUES ('event', ?, ?, 9, 1, '这是', '应用', 'REVIEWED',
                      'EXACT_PINYIN', 'ACTIVATE', 'HIGH', 'PHONETIC_ASR_ERROR', 200, 201)
            """.trimIndent(),
            arrayOf(LEARNED_RULE_ID, RECORDING_ID),
        )

        db.execSQL("DELETE FROM recordings WHERE id = ?", arrayOf(RECORDING_ID))

        assertEquals(0, db.count("correction_learning_events"))
        assertEquals(1, db.count("correction_learning_profiles"))
        assertEquals(1, db.count("correction_rules", "id = ?", arrayOf(LEARNED_RULE_ID)))
        db.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(
        table: String,
        where: String? = null,
        args: Array<Any> = emptyArray(),
    ): Int {
        val sql = buildString {
            append("SELECT COUNT(*) FROM ")
            append(table)
            if (where != null) append(" WHERE ").append(where)
        }
        return query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    companion object {
        private const val TEST_DB = "migration-7-to-8"
        private const val RECORDING_ID = "recording-before-v8"
        private const val EXACT_RULE_ID = "existing-exact-rule"
        private const val LEARNED_RULE_ID = "learned-rule"
    }
}
