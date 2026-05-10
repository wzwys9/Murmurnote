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
    suspend fun setStatus(id: String, status: ProcessingStatus, error: String? = null) =
        recordingDao.updateStatus(id, status, error)

    suspend fun insertSegments(segments: List<TranscriptSegment>) = recordingDao.insertSegments(segments)
    suspend fun insertSegment(segment: TranscriptSegment) = recordingDao.insertSegment(segment)
    suspend fun deleteSegments(recordingId: String) = recordingDao.deleteSegmentsForRecording(recordingId)
    suspend fun getSegments(recordingId: String): List<TranscriptSegment> = recordingDao.getSegments(recordingId)
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegment>> = recordingDao.observeSegments(recordingId)

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

    fun search(query: String): Flow<List<Recording>> = recordingDao.searchRecordings(query)
    fun observeTotalCount(): Flow<Int> = recordingDao.countAll()
    fun observeCountSince(since: Long): Flow<Int> = recordingDao.countSince(since)

    suspend fun deleteExpired(): Int = recordingDao.deleteExpired(System.currentTimeMillis())
}
