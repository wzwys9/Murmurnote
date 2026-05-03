package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "extracted_items",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId"), Index("type")]
)
data class ExtractedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val type: ItemType,
    val content: String,
    val deadline: Long? = null,
    val isCompleted: Boolean = false,
    val sourceTimestampMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
