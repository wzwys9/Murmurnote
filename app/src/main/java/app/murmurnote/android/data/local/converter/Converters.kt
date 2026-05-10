package app.murmurnote.android.data.local.converter

import androidx.room.TypeConverter
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.local.entity.RecordingSegmentStatus

class Converters {
    @TypeConverter fun fromSource(s: RecordingSource): String = s.name
    @TypeConverter fun toSource(s: String): RecordingSource = RecordingSource.valueOf(s)

    @TypeConverter fun fromStatus(s: ProcessingStatus): String = s.name
    @TypeConverter fun toStatus(s: String): ProcessingStatus = ProcessingStatus.valueOf(s)

    @TypeConverter fun fromItemType(t: ItemType): String = t.name
    @TypeConverter fun toItemType(t: String): ItemType = ItemType.valueOf(t)

    @TypeConverter fun fromRecordingSegmentStatus(s: RecordingSegmentStatus): String = s.name
    @TypeConverter fun toRecordingSegmentStatus(s: String): RecordingSegmentStatus =
        RecordingSegmentStatus.valueOf(s)
}
