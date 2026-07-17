package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import kotlinx.coroutines.flow.Flow

data class RecordingAudioOwnerPath(
    val id: String,
    val originalFilePath: String
)

data class RecordingSegmentOwnerPath(
    val recordingId: String,
    val filePath: String
)

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: Recording)

    @Query("UPDATE recordings SET processingStatus = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: ProcessingStatus, error: String? = null)

    @Query(
        """
        UPDATE recordings
        SET processingStatus = 'FAILED', errorMessage = :error
        WHERE processingStatus IN (
            'PENDING', 'RECORDING', 'CONVERTING', 'SPLITTING', 'TRANSCRIBING', 'EXTRACTING'
        )
        """
    )
    suspend fun markInterruptedProcessingFailed(error: String): Int

    @Query(
        """
        UPDATE recordings
        SET processingStatus = 'FAILED', errorMessage = :error
        WHERE id = :id
          AND processingStatus IN (
              'PENDING', 'RECORDING', 'CONVERTING', 'SPLITTING', 'TRANSCRIBING', 'EXTRACTING'
          )
        """
    )
    suspend fun markProcessingFailedIfInProgress(id: String, error: String): Int

    @Query("UPDATE recordings SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: String, durationMs: Long): Int

    @Query("UPDATE recordings SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: String, tags: String)

    @Query("UPDATE recordings SET archived = :archived WHERE id = :id")
    suspend fun updateArchived(id: String, archived: Boolean)

    @Query(
        """
        UPDATE recordings
        SET rawTranscript = :rawTranscript,
            correctedTranscript = :correctedTranscript,
            correctionRevision = 0,
            rawProvenance = :rawProvenance,
            asrEngineType = :asrEngineType,
            asrModelId = :asrModelId,
            asrConfigFingerprint = :asrConfigFingerprint,
            asrConfigSnapshotJson = :asrConfigSnapshotJson,
            vadPresetVersion = :vadPresetVersion,
            transcriptDirty = CASE
                WHEN summary IS NOT NULL OR draftSummary IS NOT NULL OR finalSummary IS NOT NULL
                THEN 1 ELSE 0
            END,
            transcriptEditedAt = NULL
        WHERE id = :id
          AND rawTranscript IS NULL
          AND correctionRevision = 0
        """
    )
    suspend fun finalizeModelTranscript(
        id: String,
        rawTranscript: String,
        correctedTranscript: String,
        rawProvenance: String,
        asrEngineType: String,
        asrModelId: String,
        asrConfigFingerprint: String,
        asrConfigSnapshotJson: String,
        vadPresetVersion: String
    ): Int

    @Query(
        """
        UPDATE recordings
        SET correctedTranscript = :correctedTranscript,
            correctionRevision = :newRevision,
            transcriptDirty = :transcriptDirty,
            transcriptEditedAt = :editedAt
        WHERE id = :id
          AND correctionRevision = :expectedRevision
          AND rawTranscript IS NOT NULL
        """
    )
    suspend fun updateCorrectedTranscriptRevision(
        id: String,
        expectedRevision: Long,
        newRevision: Long,
        correctedTranscript: String,
        transcriptDirty: Boolean,
        editedAt: Long
    ): Int

    @Query(
        """
        UPDATE recordings
        SET title = :title,
            summary = :summary,
            finalSummary = :summary,
            summaryTranscriptRevision = :expectedRevision,
            transcriptDirty = 0,
            processingStatus = 'COMPLETED',
            errorMessage = NULL
        WHERE id = :id
          AND correctionRevision = :expectedRevision
          AND rawTranscript IS NOT NULL
        """
    )
    suspend fun saveSummaryForRevision(
        id: String,
        expectedRevision: Long,
        title: String,
        summary: String
    ): Int

    @Query(
        """
        UPDATE recordings
        SET processingStatus = 'COMPLETED', errorMessage = :error
        WHERE id = :id
        """
    )
    suspend fun completeWithoutNewSummary(id: String, error: String?): Int

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

    // Rolling recording segments
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
        WHERE (:fromMs IS NULL OR createdAt >= :fromMs)
          AND (:toMs IS NULL OR createdAt <= :toMs)
          AND (
              (:searchSummary = 1 AND (
                  title LIKE '%' || :query || '%'
                  OR tags LIKE '%' || :query || '%'
                  OR summary LIKE '%' || :query || '%'
                  OR draftSummary LIKE '%' || :query || '%'
                  OR finalSummary LIKE '%' || :query || '%'
              ))
              OR (:searchTranscript = 1 AND correctedTranscript LIKE '%' || :query || '%')
          )
        ORDER BY createdAt DESC
    """)
    fun searchRecordingsFiltered(
        query: String,
        fromMs: Long?,
        toMs: Long?,
        searchSummary: Boolean,
        searchTranscript: Boolean
    ): Flow<List<Recording>>

    @Query(
        """
        SELECT * FROM recordings
        WHERE audioAvailable = 1
          AND keepAudio = 0
          AND audioExpiresAt IS NOT NULL
          AND audioExpiresAt <= :nowMs
          AND processingStatus IN ('COMPLETED', 'FAILED')
        ORDER BY audioExpiresAt ASC
        """
    )
    suspend fun getAudioExpiryCandidates(nowMs: Long): List<Recording>

    @Query("SELECT id, originalFilePath FROM recordings WHERE id != :recordingId AND audioAvailable = 1")
    suspend fun getOtherAvailableAudioOwners(recordingId: String): List<RecordingAudioOwnerPath>

    @Query(
        """
        SELECT recording_segments.recordingId, recording_segments.filePath FROM recording_segments
        INNER JOIN recordings ON recordings.id = recording_segments.recordingId
        WHERE recording_segments.recordingId != :recordingId
          AND recordings.audioAvailable = 1
        """
    )
    suspend fun getOtherAvailableRecordingSegments(recordingId: String): List<RecordingSegmentOwnerPath>

    @Query(
        """
        UPDATE recordings SET audioAvailable = 0
        WHERE id = :id
          AND audioAvailable = 1
          AND keepAudio = 0
          AND audioExpiresAt IS NOT NULL
          AND audioExpiresAt <= :nowMs
          AND processingStatus IN ('COMPLETED', 'FAILED')
        """
    )
    suspend fun markAudioUnavailableIfStillEligible(id: String, nowMs: Long): Int

    @Query("UPDATE recordings SET keepAudio = :keepAudio WHERE id = :id")
    suspend fun setKeepAudio(id: String, keepAudio: Boolean): Int
}
