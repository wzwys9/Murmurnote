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

    @Query(
        """
        UPDATE correction_rules
        SET isEnabled = :enabled, updatedAt = :updatedAt
        WHERE id = :id
          AND scope = 'RECORDING'
          AND scopeRecordingId = :recordingId
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
          AND matchMode = 'EXACT_TEXT'
        ORDER BY createdAt, id
        """
    )
    suspend fun getGlobalLexiconRules(): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND matchMode = 'EXACT_TEXT'
        ORDER BY createdAt, id
        """
    )
    fun observeGlobalLexiconRules(): Flow<List<CorrectionRuleEntity>>

    @Query(
        """
        UPDATE correction_rules
        SET isEnabled = :enabled, updatedAt = :updatedAt
        WHERE id = :id
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND matchMode = 'EXACT_TEXT'
        """
    )
    suspend fun setGlobalLexiconRuleEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Long,
    ): Int

    @Query(
        """
        DELETE FROM correction_rules
        WHERE id = :id
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND matchMode = 'EXACT_TEXT'
        """
    )
    suspend fun deleteGlobalLexiconRule(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecords(records: List<CorrectionRecordEntity>)

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    suspend fun getRecords(recordingId: String): List<CorrectionRecordEntity>

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    fun observeRecords(recordingId: String): Flow<List<CorrectionRecordEntity>>
}
