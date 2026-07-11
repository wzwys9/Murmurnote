package app.murmurnote.android.data.repository

import androidx.room.withTransaction
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.dao.TranscriptDao
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.TranscriptSegment
import javax.inject.Inject
import javax.inject.Singleton

/** Persists extracted items and their summary against one immutable transcript revision. */
@Singleton
class SummaryRepository @Inject constructor(
    private val database: MurmurnoteDatabase,
    private val recordingDao: RecordingDao,
    private val itemDao: ItemDao,
    private val transcriptDao: TranscriptDao
) {
    /**
     * Returns false without changing items or summaries when the transcript revision moved while
     * the network request was in flight. Room serializes this check and all writes atomically.
     */
    suspend fun saveForRevision(
        recordingId: String,
        expectedRevision: Long,
        title: String,
        summary: String,
        items: List<ExtractedItem>
    ): Boolean = database.withTransaction {
        require(items.all { it.recordingId == recordingId }) {
            "Every extracted item must belong to the summary recording"
        }
        val recording = recordingDao.getById(recordingId) ?: return@withTransaction false
        if (recording.rawTranscript == null || recording.correctionRevision != expectedRevision) {
            return@withTransaction false
        }

        itemDao.deleteForRecording(recordingId)
        if (items.isNotEmpty()) itemDao.insertAll(items)
        check(
            recordingDao.saveSummaryForRevision(
                id = recordingId,
                expectedRevision = expectedRevision,
                title = title,
                summary = summary
            ) == 1
        ) {
            "Transcript revision changed while saving summary for $recordingId"
        }
        true
    }

    suspend fun completeWithoutNewSummary(recordingId: String, error: String?): Boolean =
        recordingDao.completeWithoutNewSummary(recordingId, error) == 1

    suspend fun getExportSnapshot(recordingId: String): RecordingExportSnapshot? =
        database.withTransaction {
            val recording = recordingDao.getById(recordingId) ?: return@withTransaction null
            RecordingExportSnapshot(
                recording = recording,
                segments = transcriptDao.getSegments(recordingId),
                items = itemDao.getForRecordingSnapshot(recordingId)
            )
        }
}

data class RecordingExportSnapshot(
    val recording: Recording,
    val segments: List<TranscriptSegment>,
    val items: List<ExtractedItem>
)
