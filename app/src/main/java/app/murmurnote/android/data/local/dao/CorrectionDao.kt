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
          AND (scopeRecordingId IS NULL OR scopeRecordingId = :recordingId)
        ORDER BY createdAt, id
        """
    )
    suspend fun getApplicableRuleCandidates(recordingId: String): List<CorrectionRuleEntity>

    @Query("SELECT * FROM correction_rules WHERE isEnabled = 1 ORDER BY createdAt, id")
    suspend fun getAllEnabledRules(): List<CorrectionRuleEntity>

    @Query("SELECT * FROM correction_rules WHERE id = :id")
    suspend fun getRule(id: String): CorrectionRuleEntity?

    @Query("UPDATE correction_rules SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRuleEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scopeRecordingId IS NULL OR scopeRecordingId = :recordingId
        ORDER BY createdAt, id
        """
    )
    suspend fun getRules(recordingId: String): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT * FROM correction_rules
        WHERE scopeRecordingId IS NULL OR scopeRecordingId = :recordingId
        ORDER BY createdAt, id
        """
    )
    fun observeRules(recordingId: String): Flow<List<CorrectionRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecords(records: List<CorrectionRecordEntity>)

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    suspend fun getRecords(recordingId: String): List<CorrectionRecordEntity>

    @Query("SELECT * FROM correction_records WHERE recordingId = :recordingId ORDER BY id")
    fun observeRecords(recordingId: String): Flow<List<CorrectionRecordEntity>>
}
