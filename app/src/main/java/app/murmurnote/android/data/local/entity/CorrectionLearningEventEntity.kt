package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "correction_learning_events",
    foreignKeys = [
        ForeignKey(
            entity = CorrectionRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("ruleId"),
        Index("recordingId"),
        Index("status"),
    ],
)
data class CorrectionLearningEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val recordingId: String,
    val segmentId: Long,
    val revision: Long,
    val leftContext: String,
    val rightContext: String,
    val status: String,
    val pinyinRelation: String,
    val llmVerdict: String? = null,
    val llmConfidence: String? = null,
    val llmReasonCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long? = null,
)
