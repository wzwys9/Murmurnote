package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.murmurnote.android.data.local.entity.ApiLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiLogDao {

    @Insert
    suspend fun insert(log: ApiLog): Long

    @Query("SELECT * FROM api_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ApiLog>>

    @Query("SELECT * FROM api_logs ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ApiLog>

    @Query("DELETE FROM api_logs")
    suspend fun clear()

    /** 只保留最新 [keep] 条；其余按 id 降序裁掉，避免长期使用后表无界增长。 */
    @Query("DELETE FROM api_logs WHERE id NOT IN (SELECT id FROM api_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trimToNewest(keep: Int)
}
