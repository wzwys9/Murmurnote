package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.murmurnote.android.data.local.entity.CorrectionLearningEventEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningProfileEntity
import app.murmurnote.android.data.local.entity.CorrectionLearningProfileWithRule
import app.murmurnote.android.data.local.entity.CorrectionRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: CorrectionLearningProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: CorrectionLearningEventEntity)

    @Query("SELECT * FROM correction_learning_profiles WHERE ruleId = :ruleId")
    suspend fun getProfile(ruleId: String): CorrectionLearningProfileEntity?

    @Query("SELECT * FROM correction_learning_profiles WHERE ruleId IN (:ruleIds)")
    suspend fun getProfiles(ruleIds: List<String>): List<CorrectionLearningProfileEntity>

    @Query("SELECT * FROM correction_learning_events WHERE id = :id")
    suspend fun getEvent(id: String): CorrectionLearningEventEntity?

    @Query(
        """
        SELECT * FROM correction_learning_events
        WHERE ruleId IN (:ruleIds)
          AND status = 'REVIEWED'
        ORDER BY reviewedAt DESC, createdAt DESC, id DESC
        """,
    )
    suspend fun getReviewedEventsForRules(
        ruleIds: List<String>,
    ): List<CorrectionLearningEventEntity>

    @Query(
        """
        SELECT * FROM correction_learning_events
        WHERE status = 'PENDING'
        ORDER BY createdAt, id
        LIMIT :limit
        """,
    )
    suspend fun getPendingEvents(limit: Int): List<CorrectionLearningEventEntity>

    @Query(
        """
        SELECT correction_rules.* FROM correction_rules
        INNER JOIN correction_learning_profiles
            ON correction_learning_profiles.ruleId = correction_rules.id
        WHERE correction_rules.scope = 'GLOBAL'
          AND correction_rules.scopeRecordingId IS NULL
          AND correction_rules.origin = 'PERSONAL_LEARNING'
          AND correction_rules.matchMode = 'CONTEXTUAL_LLM'
          AND correction_rules.observedText = :observedText
          AND correction_rules.replacementText = :replacementText
        ORDER BY correction_rules.createdAt, correction_rules.id
        LIMIT 1
        """,
    )
    suspend fun findRule(
        observedText: String,
        replacementText: String,
    ): CorrectionRuleEntity?

    @Query(
        """
        SELECT correction_rules.* FROM correction_rules
        INNER JOIN correction_learning_profiles
            ON correction_learning_profiles.ruleId = correction_rules.id
        WHERE correction_rules.scope = 'GLOBAL'
          AND correction_rules.scopeRecordingId IS NULL
          AND correction_rules.origin = 'PERSONAL_LEARNING'
          AND correction_rules.matchMode = 'CONTEXTUAL_LLM'
          AND correction_rules.observedText = :observedText
        ORDER BY correction_rules.createdAt, correction_rules.id
        """,
    )
    suspend fun getRulesForObservedText(observedText: String): List<CorrectionRuleEntity>

    @Query(
        """
        UPDATE correction_learning_profiles
        SET positiveEvidenceCount = positiveEvidenceCount + 1,
            state = CASE
                WHEN state IN ('ACTIVE', 'DISABLED') THEN state
                ELSE 'PENDING_REVIEW'
            END,
            updatedAt = :updatedAt
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun addPositiveEvidence(ruleId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE correction_learning_events
        SET status = 'REVIEWED',
            pinyinRelation = :pinyinRelation,
            llmVerdict = :verdict,
            llmConfidence = :confidence,
            llmReasonCode = :reasonCode,
            reviewedAt = :reviewedAt
        WHERE id = :eventId
          AND status = 'PENDING'
        """,
    )
    suspend fun completeEventReview(
        eventId: String,
        pinyinRelation: String,
        verdict: String,
        confidence: String,
        reasonCode: String,
        reviewedAt: Long,
    ): Int

    @Query(
        """
        UPDATE correction_learning_profiles
        SET state = :state,
            observedPinyin = :observedPinyin,
            replacementPinyin = :replacementPinyin,
            pinyinRelation = :pinyinRelation,
            lastVerdict = :verdict,
            lastConfidence = :confidence,
            lastReasonCode = :reasonCode,
            lastReviewedAt = :reviewedAt,
            updatedAt = :reviewedAt
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun completeProfileReview(
        ruleId: String,
        state: String,
        observedPinyin: String?,
        replacementPinyin: String?,
        pinyinRelation: String,
        verdict: String,
        confidence: String,
        reasonCode: String,
        reviewedAt: Long,
    ): Int

    @Query(
        """
        UPDATE correction_rules
        SET isEnabled = :enabled, updatedAt = :updatedAt
        WHERE id = :ruleId
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'PERSONAL_LEARNING'
          AND matchMode = 'CONTEXTUAL_LLM'
        """,
    )
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        """
        UPDATE correction_learning_profiles
        SET state = 'DISABLED',
            negativeEvidenceCount = negativeEvidenceCount + 1,
            updatedAt = :updatedAt
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun registerNegativeFeedback(ruleId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE correction_learning_events
        SET status = 'FAILED',
            llmReasonCode = 'SUPERSEDED_BY_USER_EDIT',
            reviewedAt = :updatedAt
        WHERE ruleId = :ruleId
          AND status = 'PENDING'
        """,
    )
    suspend fun supersedePendingEvents(ruleId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE correction_learning_events
        SET status = 'FAILED',
            llmReasonCode = :reasonCode,
            reviewedAt = :updatedAt
        WHERE ruleId = :ruleId
          AND status = 'PENDING'
        """,
    )
    suspend fun failPendingEvents(
        ruleId: String,
        reasonCode: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE correction_learning_events
        SET status = 'FAILED',
            llmReasonCode = 'SUPERSEDED_BY_ACTIVATION',
            reviewedAt = :updatedAt
        WHERE ruleId = :ruleId
          AND id != :completedEventId
          AND status = 'PENDING'
        """,
    )
    suspend fun supersedeOtherPendingEvents(
        ruleId: String,
        completedEventId: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM correction_learning_profiles")
    suspend fun countProfiles(): Int

    @Query(
        """
        DELETE FROM correction_learning_events
        WHERE ruleId = :ruleId
          AND id NOT IN (
              SELECT id FROM correction_learning_events
              WHERE ruleId = :ruleId
              ORDER BY createdAt DESC, id DESC
              LIMIT :keepNewest
          )
        """,
    )
    suspend fun trimEvents(ruleId: String, keepNewest: Int): Int

    @Query(
        """
        SELECT correction_rules.* FROM correction_rules
        INNER JOIN correction_learning_profiles
            ON correction_learning_profiles.ruleId = correction_rules.id
        WHERE correction_rules.scope = 'GLOBAL'
          AND correction_rules.scopeRecordingId IS NULL
          AND correction_rules.origin = 'PERSONAL_LEARNING'
          AND correction_rules.matchMode = 'CONTEXTUAL_LLM'
          AND correction_rules.isEnabled = 1
          AND correction_learning_profiles.state = 'ACTIVE'
        ORDER BY correction_rules.createdAt, correction_rules.id
        LIMIT :limit
        """,
    )
    suspend fun getActiveRules(limit: Int = 100): List<CorrectionRuleEntity>

    @Query(
        """
        SELECT correction_rules.* FROM correction_rules
        INNER JOIN correction_learning_profiles
            ON correction_learning_profiles.ruleId = correction_rules.id
        WHERE correction_rules.scope = 'GLOBAL'
          AND correction_rules.scopeRecordingId IS NULL
          AND correction_rules.origin = 'PERSONAL_LEARNING'
          AND correction_rules.matchMode = 'CONTEXTUAL_LLM'
          AND (
            correction_rules.observedText = :userObservedText
            OR correction_rules.observedText = :userReplacementText
            OR correction_rules.replacementText = :userObservedText
          )
        ORDER BY correction_rules.createdAt, correction_rules.id
        """,
    )
    suspend fun getRulesConflictingWithUserDefinition(
        userObservedText: String,
        userReplacementText: String,
    ): List<CorrectionRuleEntity>

    @Query("SELECT * FROM correction_learning_profiles ORDER BY updatedAt DESC, ruleId")
    fun observeProfiles(): Flow<List<CorrectionLearningProfileEntity>>

    @Transaction
    @Query("SELECT * FROM correction_learning_profiles ORDER BY updatedAt DESC, ruleId")
    fun observeProfilesWithRules(): Flow<List<CorrectionLearningProfileWithRule>>

    @Query(
        """
        UPDATE correction_learning_profiles
        SET state = :state, updatedAt = :updatedAt
        WHERE ruleId = :ruleId
        """,
    )
    suspend fun setProfileState(ruleId: String, state: String, updatedAt: Long): Int

    @Query(
        """
        DELETE FROM correction_rules
        WHERE id = :ruleId
          AND scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'PERSONAL_LEARNING'
          AND matchMode = 'CONTEXTUAL_LLM'
        """,
    )
    suspend fun deleteRule(ruleId: String): Int

    @Query(
        """
        DELETE FROM correction_rules
        WHERE scope = 'GLOBAL'
          AND scopeRecordingId IS NULL
          AND origin = 'PERSONAL_LEARNING'
          AND matchMode = 'CONTEXTUAL_LLM'
        """,
    )
    suspend fun deleteAllRules(): Int
}
