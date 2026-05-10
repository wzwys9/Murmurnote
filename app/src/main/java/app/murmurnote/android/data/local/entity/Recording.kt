package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class RecordingSource { RECORDED, IMPORTED }
enum class ProcessingStatus { PENDING, RECORDING, CONVERTING, SPLITTING, TRANSCRIBING, EXTRACTING, COMPLETED, FAILED }
enum class ItemType { TODO, IDEA, NOTE, DECISION }

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
    val expirationDate: Long? = null
)
