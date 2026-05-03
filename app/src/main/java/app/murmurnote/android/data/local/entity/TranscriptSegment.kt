package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId")]
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val sequence: Int
)
