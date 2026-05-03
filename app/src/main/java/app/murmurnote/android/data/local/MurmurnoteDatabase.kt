package app.murmurnote.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.murmurnote.android.data.local.converter.Converters
import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemFts
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingFts
import app.murmurnote.android.data.local.entity.TranscriptSegment

@Database(
    entities = [
        Recording::class,
        TranscriptSegment::class,
        ExtractedItem::class,
        ApiLog::class,
        RecordingFts::class,
        ItemFts::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class MurmurnoteDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun itemDao(): ItemDao
    abstract fun apiLogDao(): ApiLogDao

    companion object {
        const val DB_NAME = "murmurnote.db"
    }
}
