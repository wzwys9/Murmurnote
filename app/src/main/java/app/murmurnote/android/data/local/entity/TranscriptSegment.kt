package app.murmurnote.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
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
    indices = [
        Index("recordingId"),
        Index(value = ["recordingId", "sequence"], unique = true)
    ]
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val rawText: String,
    val correctedText: String = rawText,
    val startMs: Long,
    val endMs: Long,
    val sequence: Int,
    val isEdited: Boolean = false,
    val editedAt: Long? = null,
    @ColumnInfo(defaultValue = "'MODEL_OUTPUT'")
    val rawProvenance: String = RawTranscriptProvenance.MODEL_OUTPUT,
    val asrConfigFingerprint: String? = null,
    val vadPresetVersion: String? = null,
    val cutReason: String? = null,
    @ColumnInfo(defaultValue = "0")
    val overlapBeforeMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val correctionRevision: Long = 0
) {
    @get:Ignore
    val text: String
        get() = correctedText

    @Ignore
    constructor(
        id: Long = 0,
        recordingId: String,
        text: String,
        startMs: Long,
        endMs: Long,
        sequence: Int,
        isEdited: Boolean = false,
        editedAt: Long? = null
    ) : this(
        id = id,
        recordingId = recordingId,
        rawText = text,
        correctedText = text,
        startMs = startMs,
        endMs = endMs,
        sequence = sequence,
        isEdited = isEdited,
        editedAt = editedAt
    )
}
