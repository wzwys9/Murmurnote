package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "correction_records",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CorrectionRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceRuleId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("recordingId"),
        Index("sourceRuleId"),
        Index(value = ["recordingId", "revision"])
    ]
)
data class CorrectionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val revision: Long,
    val sourceRuleId: String? = null,
    val rawStartCodePoint: Int,
    val rawEndCodePointExclusive: Int,
    val originalText: String,
    val replacementText: String,
    val decision: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
)
