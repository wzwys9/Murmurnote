package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.murmurnote.android.data.local.entity.TranscriptRevisionEntity
import app.murmurnote.android.data.local.entity.TranscriptSegment
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertModelSegment(segment: TranscriptSegment): Long

    @Query(
        """
        SELECT * FROM transcript_segments
        WHERE recordingId = :recordingId AND sequence = :sequence
        """
    )
    suspend fun getSegmentBySequence(recordingId: String, sequence: Int): TranscriptSegment?

    @Query(
        """
        SELECT * FROM transcript_segments
        WHERE recordingId = :recordingId AND id = :segmentId
        """
    )
    suspend fun getSegment(recordingId: String, segmentId: Long): TranscriptSegment?

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY sequence")
    suspend fun getSegments(recordingId: String): List<TranscriptSegment>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY sequence")
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegment>>

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteProvisionalSegments(recordingId: String): Int

    @Query(
        """
        UPDATE transcript_segments
        SET correctedText = :correctedText,
            correctionRevision = 0
        WHERE id = :segmentId
          AND recordingId = :recordingId
          AND correctionRevision = 0
          AND isEdited = 0
        """
    )
    suspend fun setInitialCorrection(
        recordingId: String,
        segmentId: Long,
        correctedText: String
    ): Int

    @Query(
        """
        UPDATE transcript_segments
        SET correctedText = :correctedText,
            isEdited = 1,
            editedAt = :editedAt,
            correctionRevision = :newRevision
        WHERE id = :segmentId
          AND recordingId = :recordingId
          AND correctionRevision = :expectedSegmentRevision
        """
    )
    suspend fun setManualCorrection(
        recordingId: String,
        segmentId: Long,
        expectedSegmentRevision: Long,
        correctedText: String,
        editedAt: Long,
        newRevision: Long
    ): Int

    @Query(
        """
        UPDATE transcript_segments
        SET correctedText = :correctedText,
            correctionRevision = :newRevision
        WHERE id = :segmentId
          AND recordingId = :recordingId
          AND correctionRevision = :expectedSegmentRevision
          AND isEdited = 0
        """,
    )
    suspend fun setAutomatedCorrection(
        recordingId: String,
        segmentId: Long,
        expectedSegmentRevision: Long,
        correctedText: String,
        newRevision: Long,
    ): Int

    @Query(
        """
        UPDATE transcript_segments
        SET correctedText = rawText,
            isEdited = 0,
            editedAt = NULL,
            correctionRevision = :newRevision
        WHERE recordingId = :recordingId
        """
    )
    suspend fun revertCorrectionsToRaw(recordingId: String, newRevision: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: TranscriptRevisionEntity): Long

    @Query("SELECT * FROM transcript_revisions WHERE recordingId = :recordingId ORDER BY revision")
    suspend fun getRevisions(recordingId: String): List<TranscriptRevisionEntity>

    @Query("SELECT * FROM transcript_revisions WHERE recordingId = :recordingId ORDER BY revision")
    fun observeRevisions(recordingId: String): Flow<List<TranscriptRevisionEntity>>
}
