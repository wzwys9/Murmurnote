package app.murmurnote.android.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MurmurnoteDatabase::class.java,
    )

    @Test
    fun migrate8To9_classifiesExistingRulesWithoutChangingTheirBehavior() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertRule(
                id = USER_RULE_ID,
                observedText = "木木笔记",
                replacementText = "声记应用",
                matchMode = "EXACT_TEXT",
            )
            insertRule(
                id = LEARNED_RULE_ID,
                observedText = "生记",
                replacementText = "声记",
                matchMode = "CONTEXTUAL_LLM",
            )
            insert(
                "correction_learning_profiles",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("ruleId", LEARNED_RULE_ID)
                    put("state", "ACTIVE")
                    put("positiveEvidenceCount", 2)
                    put("negativeEvidenceCount", 0)
                    put("observedPinyin", "sheng ji")
                    put("replacementPinyin", "sheng ji")
                    put("pinyinRelation", "EXACT_PINYIN")
                    put("lastVerdict", "ACTIVATE")
                    put("lastConfidence", "HIGH")
                    put("lastReasonCode", "PHONETIC_ASR_ERROR")
                    put("lastReviewedAt", 103L)
                    put("createdAt", 102L)
                    put("updatedAt", 103L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MurmurnoteDatabase.MIGRATION_8_9,
        )

        db.query(
            "SELECT id, matchMode, origin FROM correction_rules ORDER BY id",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(LEARNED_RULE_ID, cursor.getString(0))
            assertEquals("CONTEXTUAL_LLM", cursor.getString(1))
            assertEquals("PERSONAL_LEARNING", cursor.getString(2))

            assertTrue(cursor.moveToNext())
            assertEquals(USER_RULE_ID, cursor.getString(0))
            assertEquals("EXACT_TEXT", cursor.getString(1))
            assertEquals("USER_DEFINED", cursor.getString(2))
        }

        db.query(
            "SELECT positiveEvidenceCount, state FROM correction_learning_profiles WHERE ruleId = ?",
            arrayOf(LEARNED_RULE_ID),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals("ACTIVE", cursor.getString(1))
        }
        db.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertRule(
        id: String,
        observedText: String,
        replacementText: String,
        matchMode: String,
    ) {
        insert(
            "correction_rules",
            SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("id", id)
                put("observedText", observedText)
                put("replacementText", replacementText)
                put("scope", "GLOBAL")
                putNull("scopeRecordingId")
                put("matchMode", matchMode)
                put("isEnabled", true)
                put("createdAt", 101L)
                put("updatedAt", 101L)
            },
        )
    }

    private companion object {
        const val TEST_DB = "migration-8-to-9"
        const val USER_RULE_ID = "user-rule"
        const val LEARNED_RULE_ID = "learned-rule"
    }
}
