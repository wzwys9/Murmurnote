package app.murmurnote.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.murmurnote.android.data.local.converter.Converters
import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemFts
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingFts
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.TranscriptSegment

@Database(
    entities = [
        Recording::class,
        RecordingSegment::class,
        TranscriptSegment::class,
        ExtractedItem::class,
        ApiLog::class,
        RecordingFts::class,
        ItemFts::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MurmurnoteDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
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
    }
}
