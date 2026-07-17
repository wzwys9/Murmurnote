package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.murmurnote.android.data.local.entity.CorrectionRecordEntity
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: CorrectionRuleEntity)

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE isEnabled = 1
          AND origin = 'USER_DEFINED'
          AND matchMode = 'EXACT_TEXT'
          AND (
            (scope = 'GLOBAL' AND scopeRecordingId IS NULL)
            OR (scope = 'RECORDING' AND scopeRecordingId = :recordingId)
          )
        ORDER BY createdAt, id
        """
    )
    suspend fun getApplicableRuleCandidates(recordingId: String): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE isEnabled = 1
          AND origin = 'USER_DEFINED'
          AND scope = 'RECORDING'
          AND scopeRecordingId = :recordingId
          AND matchMode = 'EXACT_TEXT'
        ORDER BY createdAt, id
        """
    )
    suspend fun getEnabledRecordingRuleCandidates(
        recordingId: String,
    ): List<CorrectionRuleEntity>

    @Query("SELECT * FROM correction_rules WHERE id = :id")
    suspend fun getRule(id: String): CorrectionRuleEntity?

    @Query("SELECT * FROM correction_rules WHERE id IN (:ids)")
    suspend fun getRules(ids: List<String>): List<CorrectionRuleEntity>

    @Query(
        """
        UPDATE correction_rules
        SET isEnabled = :enabled, updatedAt = :updatedAt
        WHERE id = :id
          AND scope = 'RECORDING'
          AND scopeRecordingId = :recordingId
          AND origin = 'USER_DEFINED'
          AND matchMode = 'EXACT_TEXT'
        """
    )
    suspend fun setRecordingRuleEnabled(
        recordingId: String,
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'RECORDING'
          AND scopeRecordingId = :recordingId
          AND origin = 'USER_DEFINED'
          AND matchMode = 'EXACT_TEXT'
        ORDER BY createdAt, id
        """
    )
    suspend fun getRecordingRules(recordingId: String): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'RECORDING'
          AND scopeRecordingId = :recordingId
          AND origin = 'USER_DEFINED'
          AND matchMode = 'EXACT_TEXT'
        ORDER BY createdAt, id
        """
    )
    fun observeRecordingRules(recordingId: String): Flow<List<CorrectionRuleEntity>>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
        ORDER BY createdAt, id
        """
    )
    suspend fun getUserDefinedRules(): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
        ORDER BY createdAt, id
        """
    )
    fun observeUserDefinedRules(): Flow<List<CorrectionRuleEntity>>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
          AND isEnabled = 1
        ORDER BY createdAt, id
        """
    )
    suspend fun getEnabledUserDefinedRules(): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
          AND matchMode = 'CONTEXTUAL_LLM'
          AND isEnabled = 1
        ORDER BY createdAt, id
        LIMIT :limit
        """
    )
    suspend fun getEnabledUserContextualRules(limit: Int): List<CorrectionRuleEntity>

    @Query(
        """
        UPDATE correction_rules
        SET isEnabled = :enabled, updatedAt = :updatedAt
        WHERE id = :id
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
        """
    )
    suspend fun setUserDefinedRuleEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE correction_rules
        SET matchMode = :matchMode, updatedAt = :updatedAt
        WHERE id = :id
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
        """
    )
    suspend fun setUserDefinedRuleMatchMode(
        id: String,
        matchMode: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        DELETE FROM correction_rules
        WHERE id = :id
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'USER_DEFINED'
        """
    )
    suspend fun deleteUserDefinedRule(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecords(records: List<CorrectionRecordEntity>)

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    suspend fun getRecords(recordingId: String): List<CorrectionRecordEntity>

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    fun observeRecords(recordingId: String): Flow<List<CorrectionRecordEntity>>

    @Query(
        """
        SELECT * FROM correction_records
        WHERE recordingId = :recordingId
          AND revision = :revision
          AND decision = 'APPLIED'
          AND sourceRuleId IS NOT NULL
        ORDER BY id
        """,
    )
    suspend fun getAppliedRecordsForRevision(
        recordingId: String,
        revision: Long,
    ): List<CorrectionRecordEntity>

    @Query(
        """
        SELECT * FROM correction_records
        WHERE recordingId = :recordingId
          AND revision = (
              SELECT MAX(revision) FROM correction_records
              WHERE recordingId = :recordingId
                AND revision <= :maxRevision
                AND decision = 'APPLIED'
                AND reason = 'PERSONALIZED_LLM_CONTEXT_APPLIED'
                AND rawStartCodePoint >= :segmentRawStart
                AND rawEndCodePointExclusive <= :segmentRawEnd
          )
          AND decision = 'APPLIED'
          AND reason = 'PERSONALIZED_LLM_CONTEXT_APPLIED'
          AND rawStartCodePoint >= :segmentRawStart
          AND rawEndCodePointExclusive <= :segmentRawEnd
        ORDER BY rawStartCodePoint, id
        """,
    )
    suspend fun getLatestAppliedPersonalizedRecordsForSegment(
        recordingId: String,
        maxRevision: Long,
        segmentRawStart: Int,
        segmentRawEnd: Int,
    ): List<CorrectionRecordEntity>
}
