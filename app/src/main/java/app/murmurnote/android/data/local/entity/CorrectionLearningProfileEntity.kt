package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "correction_learning_profiles",
    foreignKeys = [
        ForeignKey(
            entity = CorrectionRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("state")],
)
data class CorrectionLearningProfileEntity(
    @PrimaryKey val ruleId: String,
    val state: String,
    val positiveEvidenceCount: Int,
    val negativeEvidenceCount: Int,
    val observedPinyin: String? = null,
    val replacementPinyin: String? = null,
    val pinyinRelation: String,
    val lastVerdict: String? = null,
    val lastConfidence: String? = null,
    val lastReasonCode: String? = null,
    val lastReviewedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)
