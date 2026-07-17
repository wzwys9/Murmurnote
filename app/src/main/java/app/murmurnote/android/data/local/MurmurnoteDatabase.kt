package app.murmurnote.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.murmurnote.android.data.local.converter.Converters
import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.dao.CorrectionDao
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.PersonalCorrectionDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.data.local.entity.CorrectionRecordEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningEventEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningProfileEntity
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemFts
import app.murmurnote.android.data.local.entity.LegacyTranscriptSegmentConflict
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingFts
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.TranscriptRevisionEntity
import app.murmurnote.android.data.local.entity.TranscriptSegment

@Database(
    entities = [
        Recording::class,
        RecordingSegment::class,
        TranscriptSegment::class,
        TranscriptRevisionEntity::class,
        CorrectionRuleEntity::class,
        CorrectionRecordEntity::class,
        CorrectionLearningProfileEntity::class,
        CorrectionLearningEventEntity::class,
        LegacyTranscriptSegmentConflict::class,
        ExtractedItem::class,
        ApiLog::class,
        RecordingFts::class,
        ItemFts::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MurmurnoteDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun personalCorrectionDao(): PersonalCorrectionDao
    abstract fun itemDao(): ItemDao
    abstract fun apiLogDao(): ApiLogDao

    companion object {
        const val DB_NAME = "murmurnote.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_segments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `transcriptText` TEXT,
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recording_segments_recordingId` " +
                        "ON `recording_segments` (`recordingId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_recording_segments_recordingId_sequence` " +
                        "ON `recording_segments` (`recordingId`, `sequence`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `draftSummary` TEXT")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `finalSummary` TEXT")
                db.execSQL(
                    "UPDATE `recordings` SET `finalSummary` = `summary` " +
                        "WHERE `summary` IS NOT NULL AND `finalSummary` IS NULL"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `transcriptDirty` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `transcriptEditedAt` INTEGER")
                db.execSQL("ALTER TABLE `transcript_segments` ADD COLUMN `isEdited` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transcript_segments` ADD COLUMN `editedAt` INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Introduces immutable model output and derived corrected revisions without fabricating
         * provenance for legacy rows. v5 allowed duplicate transcript sequence numbers; the row
         * with the greatest id remains active (matching the old runtime normalization), while
         * every older byte sequence is retained in a managed conflict archive.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateRecordingColumns(db)
                migrateRecordingSegmentColumns(db)
                createRevisionAndCorrectionTables(db)
                migrateTranscriptSegmentsLosslessly(db)
                seedLegacyTranscriptRevisions(db)

                // v5 diagnostics could contain prompts, transcript excerpts, and response bodies.
                // Keeping metadata is useful; retaining those bodies after the privacy upgrade is not.
                db.execSQL(
                    "UPDATE `api_logs` SET `requestBody` = NULL, `responseBody` = NULL, " +
                        "`url` = '<redacted>', `errorMessage` = NULL"
                )
            }
        }

        /**
         * v7 keeps the v6 schema but scrubs legacy diagnostics again. Some devices may already
         * have opened v6 before the privacy policy stopped persisting bodies and URL details.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `api_logs` SET `requestBody` = NULL, `responseBody` = NULL, " +
                        "`url` = '<redacted>', `errorMessage` = NULL"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `correction_learning_profiles` (
                        `ruleId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `positiveEvidenceCount` INTEGER NOT NULL,
                        `negativeEvidenceCount` INTEGER NOT NULL,
                        `observedPinyin` TEXT,
                        `replacementPinyin` TEXT,
                        `pinyinRelation` TEXT NOT NULL,
                        `lastVerdict` TEXT,
                        `lastConfidence` TEXT,
                        `lastReasonCode` TEXT,
                        `lastReviewedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`ruleId`),
                        FOREIGN KEY(`ruleId`) REFERENCES `correction_rules`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correction_learning_profiles_state` " +
                        "ON `correction_learning_profiles` (`state`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `correction_learning_events` (
                        `id` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `recordingId` TEXT NOT NULL,
                        `segmentId` INTEGER NOT NULL,
                        `revision` INTEGER NOT NULL,
                        `leftContext` TEXT NOT NULL,
                        `rightContext` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `pinyinRelation` TEXT NOT NULL,
                        `llmVerdict` TEXT,
                        `llmConfidence` TEXT,
                        `llmReasonCode` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `reviewedAt` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`ruleId`) REFERENCES `correction_rules`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correction_learning_events_ruleId` " +
                        "ON `correction_learning_events` (`ruleId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correction_learning_events_recordingId` " +
                        "ON `correction_learning_events` (`recordingId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correction_learning_events_status` " +
                        "ON `correction_learning_events` (`status`)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `correction_rules` ADD COLUMN `origin` " +
                        "TEXT NOT NULL DEFAULT 'USER_DEFINED'"
                )
                db.execSQL(
                    """
                    UPDATE `correction_rules`
                    SET `origin` = 'PERSONAL_LEARNING'
                    WHERE `id` IN (SELECT `ruleId` FROM `correction_learning_profiles`)
                    """.trimIndent()
                )
            }
        }

        private fun migrateRecordingColumns(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `correctedTranscript` TEXT")
            db.execSQL(
                "ALTER TABLE `recordings` ADD COLUMN `correctionRevision` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `summaryTranscriptRevision` INTEGER")
            db.execSQL(
                "ALTER TABLE `recordings` ADD COLUMN `rawProvenance` " +
                    "TEXT NOT NULL DEFAULT 'MODEL_OUTPUT'"
            )
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `asrEngineType` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `asrModelId` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `asrConfigFingerprint` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `asrConfigSnapshotJson` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `vadPresetVersion` TEXT")
            db.execSQL("ALTER TABLE `recordings` ADD COLUMN `audioExpiresAt` INTEGER")
            db.execSQL(
                "ALTER TABLE `recordings` ADD COLUMN `keepAudio` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `recordings` ADD COLUMN `audioAvailable` INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                """
                UPDATE `recordings`
                SET `correctedTranscript` = `rawTranscript`,
                    `correctionRevision` = 0,
                    `summaryTranscriptRevision` = CASE
                        WHEN `rawTranscript` IS NOT NULL
                             AND (`summary` IS NOT NULL OR `finalSummary` IS NOT NULL) THEN 0
                        ELSE NULL
                    END,
                    `rawProvenance` = CASE
                        WHEN `rawTranscript` IS NOT NULL THEN 'LEGACY_PROVENANCE_UNKNOWN'
                        ELSE 'MODEL_OUTPUT'
                    END,
                    `audioExpiresAt` = `expirationDate`,
                    `expirationDate` = NULL,
                    `keepAudio` = 0,
                    `audioAvailable` = 1
                """.trimIndent()
            )
        }

        private fun migrateRecordingSegmentColumns(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `recording_segments` ADD COLUMN `asrConfigFingerprint` TEXT")
            db.execSQL("ALTER TABLE `recording_segments` ADD COLUMN `vadPresetVersion` TEXT")
            db.execSQL("ALTER TABLE `recording_segments` ADD COLUMN `cutReason` TEXT")
            db.execSQL(
                "ALTER TABLE `recording_segments` ADD COLUMN `overlapBeforeMs` " +
                    "INTEGER NOT NULL DEFAULT 0"
            )
        }

        private fun createRevisionAndCorrectionTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transcript_revisions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `recordingId` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transcript_revisions_recordingId` " +
                    "ON `transcript_revisions` (`recordingId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_revisions_recordingId_revision` " +
                    "ON `transcript_revisions` (`recordingId`, `revision`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `correction_rules` (
                    `id` TEXT NOT NULL,
                    `observedText` TEXT NOT NULL,
                    `replacementText` TEXT NOT NULL,
                    `scope` TEXT NOT NULL,
                    `scopeRecordingId` TEXT,
                    `matchMode` TEXT NOT NULL DEFAULT 'EXACT_TEXT',
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`scopeRecordingId`) REFERENCES `recordings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_correction_rules_scopeRecordingId` " +
                    "ON `correction_rules` (`scopeRecordingId`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `correction_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `recordingId` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `sourceRuleId` TEXT,
                    `rawStartCodePoint` INTEGER NOT NULL,
                    `rawEndCodePointExclusive` INTEGER NOT NULL,
                    `originalText` TEXT NOT NULL,
                    `replacementText` TEXT NOT NULL,
                    `decision` TEXT NOT NULL,
                    `reason` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceRuleId`) REFERENCES `correction_rules`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_correction_records_recordingId` " +
                    "ON `correction_records` (`recordingId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_correction_records_sourceRuleId` " +
                    "ON `correction_records` (`sourceRuleId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_correction_records_recordingId_revision` " +
                    "ON `correction_records` (`recordingId`, `revision`)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `legacy_transcript_segment_conflicts` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `recordingId` TEXT NOT NULL,
                    `legacySegmentId` INTEGER NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `startMs` INTEGER NOT NULL,
                    `endMs` INTEGER NOT NULL,
                    `isEdited` INTEGER NOT NULL,
                    `editedAt` INTEGER,
                    `reason` TEXT NOT NULL,
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_legacy_transcript_segment_conflicts_recordingId` " +
                    "ON `legacy_transcript_segment_conflicts` (`recordingId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_legacy_transcript_segment_conflicts_legacySegmentId` " +
                    "ON `legacy_transcript_segment_conflicts` (`legacySegmentId`)"
            )
        }

        private fun migrateTranscriptSegmentsLosslessly(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO `legacy_transcript_segment_conflicts` (
                    `recordingId`, `legacySegmentId`, `sequence`, `text`, `startMs`, `endMs`,
                    `isEdited`, `editedAt`, `reason`
                )
                SELECT legacy.`recordingId`, legacy.`id`, legacy.`sequence`, legacy.`text`,
                       legacy.`startMs`, legacy.`endMs`, legacy.`isEdited`, legacy.`editedAt`,
                       'DUPLICATE_RECORDING_SEQUENCE'
                FROM `transcript_segments` AS legacy
                WHERE EXISTS (
                    SELECT 1
                    FROM `transcript_segments` AS newer
                    WHERE newer.`recordingId` = legacy.`recordingId`
                      AND newer.`sequence` = legacy.`sequence`
                      AND newer.`id` > legacy.`id`
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE `transcript_segments_v6` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `recordingId` TEXT NOT NULL,
                    `rawText` TEXT NOT NULL,
                    `correctedText` TEXT NOT NULL,
                    `startMs` INTEGER NOT NULL,
                    `endMs` INTEGER NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `isEdited` INTEGER NOT NULL,
                    `editedAt` INTEGER,
                    `rawProvenance` TEXT NOT NULL DEFAULT 'MODEL_OUTPUT',
                    `asrConfigFingerprint` TEXT,
                    `vadPresetVersion` TEXT,
                    `cutReason` TEXT,
                    `overlapBeforeMs` INTEGER NOT NULL DEFAULT 0,
                    `correctionRevision` INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `transcript_segments_v6` (
                    `id`, `recordingId`, `rawText`, `correctedText`, `startMs`, `endMs`,
                    `sequence`, `isEdited`, `editedAt`, `rawProvenance`,
                    `asrConfigFingerprint`, `vadPresetVersion`, `cutReason`,
                    `overlapBeforeMs`, `correctionRevision`
                )
                SELECT legacy.`id`, legacy.`recordingId`, legacy.`text`, legacy.`text`,
                       legacy.`startMs`, legacy.`endMs`, legacy.`sequence`, legacy.`isEdited`,
                       legacy.`editedAt`, 'LEGACY_PROVENANCE_UNKNOWN', NULL,
                       NULL, NULL, 0, 0
                FROM `transcript_segments` AS legacy
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM `transcript_segments` AS newer
                    WHERE newer.`recordingId` = legacy.`recordingId`
                      AND newer.`sequence` = legacy.`sequence`
                      AND newer.`id` > legacy.`id`
                )
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `transcript_segments`")
            db.execSQL("ALTER TABLE `transcript_segments_v6` RENAME TO `transcript_segments`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transcript_segments_recordingId` " +
                    "ON `transcript_segments` (`recordingId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_segments_recordingId_sequence` " +
                    "ON `transcript_segments` (`recordingId`, `sequence`)"
            )
        }

        private fun seedLegacyTranscriptRevisions(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT INTO `transcript_revisions` (
                    `recordingId`, `revision`, `text`, `source`, `createdAt`
                )
                SELECT `id`, 0, `rawTranscript`, 'LEGACY_MIGRATION',
                       COALESCE(`transcriptEditedAt`, `createdAt`)
                FROM `recordings`
                WHERE `rawTranscript` IS NOT NULL
                """.trimIndent()
            )
        }
    }
}
