package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.TranscriptSegment
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET processingStatus = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: ProcessingStatus, error: String? = null)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: String): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: String): Flow<Recording?>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT COUNT(*) FROM recordings WHERE createdAt >= :sinceMs")
    fun countSince(sinceMs: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM recordings")
    fun countAll(): Flow<Int>

    // Segments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranscriptSegment)

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY sequence ASC")
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY sequence ASC")
    suspend fun getSegments(recordingId: String): List<TranscriptSegment>

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteSegmentsForRecording(recordingId: String)

    // Rolling recording segments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordingSegments(segments: List<RecordingSegment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordingSegment(segment: RecordingSegment)

    @Query("SELECT * FROM recording_segments WHERE recordingId = :recordingId ORDER BY sequence ASC")
    fun observeRecordingSegments(recordingId: String): Flow<List<RecordingSegment>>

    @Query("SELECT * FROM recording_segments WHERE recordingId = :recordingId ORDER BY sequence ASC")
    suspend fun getRecordingSegments(recordingId: String): List<RecordingSegment>

    @Query("DELETE FROM recording_segments WHERE recordingId = :recordingId")
    suspend fun deleteRecordingSegmentsForRecording(recordingId: String)

    // 搜索改用 LIKE:FTS4 默认 simple tokenizer 不支持中文(把 CJK 视为分隔符,索引不到任何中文 token),
    // 个人语音备忘录数据量小(几百条上限),LIKE 在 title/summary/transcript 上扫一遍依然亚毫秒级,
    // 但能正确命中中英文及混合输入。recordings_fts 表保留不动,只是不再被这条查询使用。
    @Query("""
        SELECT * FROM recordings
        WHERE title LIKE '%' || :query || '%'
           OR summary LIKE '%' || :query || '%'
           OR draftSummary LIKE '%' || :query || '%'
           OR finalSummary LIKE '%' || :query || '%'
           OR rawTranscript LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun searchRecordings(query: String): Flow<List<Recording>>

    @Query("DELETE FROM recordings WHERE expirationDate IS NOT NULL AND expirationDate < :nowMs")
    suspend fun deleteExpired(nowMs: Long): Int
}
