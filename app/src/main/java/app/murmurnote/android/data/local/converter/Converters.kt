package app.murmurnote.android.data.local.converter

import androidx.room.TypeConverter
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.RecordingSource

class Converters {
    @TypeConverter fun fromSource(s: RecordingSource): String = s.name
    @TypeConverter fun toSource(s: String): RecordingSource = RecordingSource.valueOf(s)

    @TypeConverter fun fromStatus(s: ProcessingStatus): String = s.name
    @TypeConverter fun toStatus(s: String): ProcessingStatus = ProcessingStatus.valueOf(s)

    @TypeConverter fun fromItemType(t: ItemType): String = t.name
    @TypeConverter fun toItemType(t: String): ItemType = ItemType.valueOf(t)
}
