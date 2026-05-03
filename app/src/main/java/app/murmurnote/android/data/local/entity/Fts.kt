package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "recordings_fts")
@Fts4(contentEntity = Recording::class)
data class RecordingFts(
    val title: String,
    val rawTranscript: String,
    val summary: String
)

@Entity(tableName = "items_fts")
@Fts4(contentEntity = ExtractedItem::class)
data class ItemFts(
    val content: String
)
