package app.murmurnote.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "api_logs")
data class ApiLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val apiName: String,
    val method: String,
    val url: String,
    val requestBody: String?,
    val responseCode: Int,
    val responseBody: String?,
    val durationMs: Long,
    val errorMessage: String? = null
)
