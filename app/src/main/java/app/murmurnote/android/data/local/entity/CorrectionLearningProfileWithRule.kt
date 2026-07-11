package app.murmurnote.android.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class CorrectionLearningProfileWithRule(
    @Embedded val profile: CorrectionLearningProfileEntity,
    @Relation(
        parentColumn = "ruleId",
        entityColumn = "id",
    )
    val rule: CorrectionRuleEntity,
)
