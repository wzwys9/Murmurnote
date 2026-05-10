package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingSegmentStatus { READY, TRANSCRIBING, TRANSCRIBED, FAILED }

@Entity(
    tableName = "recording_segments",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("recordingId"),
        Index(value = ["recordingId", "sequence"], unique = true)
    ]
)
data class RecordingSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val sequence: Int,
    val filePath: String,
    val startMs: Long,
    val endMs: Long,
    val status: RecordingSegmentStatus,
    val errorMessage: String? = null,
    val transcriptText: String? = null
)
