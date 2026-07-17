package app.murmurnote.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "correction_rules",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["scopeRecordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("scopeRecordingId")]
)
data class CorrectionRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val observedText: String,
    val replacementText: String,
    val scope: String,
    val scopeRecordingId: String? = null,
    @ColumnInfo(defaultValue = "'EXACT_TEXT'")
    val matchMode: String = "EXACT_TEXT",
    @ColumnInfo(defaultValue = "'USER_DEFINED'")
    val origin: String = "USER_DEFINED",
    @ColumnInfo(defaultValue = "1")
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)
