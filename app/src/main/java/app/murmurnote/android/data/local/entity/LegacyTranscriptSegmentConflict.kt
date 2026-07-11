package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "legacy_transcript_segment_conflicts",
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
        Index(value = ["legacySegmentId"], unique = true)
    ]
)
data class LegacyTranscriptSegmentConflict(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val legacySegmentId: Long,
    val sequence: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isEdited: Boolean,
    val editedAt: Long?,
    val reason: String
)
