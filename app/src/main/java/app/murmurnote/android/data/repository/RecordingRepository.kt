package app.murmurnote.android.data.repository

import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.TranscriptSegment
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val recordingDao: RecordingDao
) {
    fun observeAll(): Flow<List<Recording>> = recordingDao.observeAll()
    fun observe(id: String): Flow<Recording?> = recordingDao.observeById(id)
    suspend fun get(id: String): Recording? = recordingDao.getById(id)

    suspend fun insert(recording: Recording) = recordingDao.insert(recording)
    suspend fun update(recording: Recording) = recordingDao.update(recording)
    suspend fun delete(id: String) = recordingDao.deleteById(id)
    suspend fun updateTags(id: String, tags: List<String>) =
        recordingDao.updateTags(id, tags.toTagString())
    suspend fun updateArchived(id: String, archived: Boolean) =
        recordingDao.updateArchived(id, archived)
    suspend fun setStatus(id: String, status: ProcessingStatus, error: String? = null) =
        recordingDao.updateStatus(id, status, error)

    suspend fun insertSegments(segments: List<TranscriptSegment>) = recordingDao.insertSegments(segments)
    suspend fun insertSegment(segment: TranscriptSegment) = recordingDao.insertSegment(segment)
    suspend fun deleteSegments(recordingId: String) = recordingDao.deleteSegmentsForRecording(recordingId)
    suspend fun getSegments(recordingId: String): List<TranscriptSegment> = recordingDao.getSegments(recordingId)
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegment>> = recordingDao.observeSegments(recordingId)
    suspend fun updateTranscriptSegmentText(recordingId: String, segmentId: Long, text: String) {
        val editedAt = System.currentTimeMillis()
        recordingDao.updateTranscriptSegmentText(segmentId, text, editedAt)
        val rawTranscript = recordingDao.getSegments(recordingId)
            .sortedBy { it.sequence }
            .joinToString("\n") { it.text }
        recordingDao.markTranscriptEdited(recordingId, rawTranscript, editedAt)
    }

    suspend fun insertRecordingSegments(segments: List<RecordingSegment>) =
        recordingDao.insertRecordingSegments(segments)
    suspend fun insertRecordingSegment(segment: RecordingSegment) =
        recordingDao.insertRecordingSegment(segment)
    suspend fun deleteRecordingSegments(recordingId: String) =
        recordingDao.deleteRecordingSegmentsForRecording(recordingId)
    suspend fun getRecordingSegments(recordingId: String): List<RecordingSegment> =
        recordingDao.getRecordingSegments(recordingId)
    fun observeRecordingSegments(recordingId: String): Flow<List<RecordingSegment>> =
        recordingDao.observeRecordingSegments(recordingId)
    suspend fun markRecordingSegmentsTranscribed(recordingId: String) =
        recordingDao.markRecordingSegmentsTranscribed(recordingId)

    fun search(query: String): Flow<List<Recording>> = recordingDao.searchRecordings(query)
    fun searchFiltered(
        query: String,
        fromMs: Long?,
        toMs: Long?,
        searchSummary: Boolean,
        searchTranscript: Boolean
    ): Flow<List<Recording>> = recordingDao.searchRecordingsFiltered(
        query = query,
        fromMs = fromMs,
        toMs = toMs,
        searchSummary = searchSummary,
        searchTranscript = searchTranscript
    )
    fun observeTotalCount(): Flow<Int> = recordingDao.countAll()
    fun observeCountSince(since: Long): Flow<Int> = recordingDao.countSince(since)

    suspend fun deleteExpired(): Int = recordingDao.deleteExpired(System.currentTimeMillis())

    private fun List<String>.toTagString(): String =
        map { it.trim().replace(",", " ").take(24) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
}
