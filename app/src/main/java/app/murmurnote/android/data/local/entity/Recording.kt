package app.murmurnote.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class RecordingSource { RECORDED, IMPORTED }
enum class ProcessingStatus { PENDING, RECORDING, CONVERTING, SPLITTING, TRANSCRIBING, EXTRACTING, COMPLETED, FAILED }
enum class ItemType { TODO, IDEA, NOTE, DECISION }

object RawTranscriptProvenance {
    const val MODEL_OUTPUT = "MODEL_OUTPUT"
    const val LEGACY_PROVENANCE_UNKNOWN = "LEGACY_PROVENANCE_UNKNOWN"
}

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val originalFilePath: String,
    val durationMs: Long,
    val createdAt: Long,
    val source: RecordingSource,
    val processingStatus: ProcessingStatus,
    val errorMessage: String? = null,
    val summary: String? = null,
    val draftSummary: String? = null,
    val finalSummary: String? = null,
    val transcriptDirty: Boolean = false,
    val transcriptEditedAt: Long? = null,
    val rawTranscript: String? = null,
    val tags: String = "",
    val archived: Boolean = false,
    val expirationDate: Long? = null,
    val correctedTranscript: String? = rawTranscript,
    @ColumnInfo(defaultValue = "0")
    val correctionRevision: Long = 0,
    val summaryTranscriptRevision: Long? = null,
    @ColumnInfo(defaultValue = "'MODEL_OUTPUT'")
    val rawProvenance: String = RawTranscriptProvenance.MODEL_OUTPUT,
    val asrEngineType: String? = null,
    val asrModelId: String? = null,
    val asrConfigFingerprint: String? = null,
    val asrConfigSnapshotJson: String? = null,
    val vadPresetVersion: String? = null,
    val audioExpiresAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val keepAudio: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val audioAvailable: Boolean = true
)
