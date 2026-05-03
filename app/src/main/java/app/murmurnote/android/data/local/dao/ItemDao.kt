package app.murmurnote.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ExtractedItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExtractedItem>)

    @Query("UPDATE extracted_items SET isCompleted = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM extracted_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM extracted_items WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: String)

    @Query("SELECT * FROM extracted_items WHERE recordingId = :recordingId ORDER BY id ASC")
    fun observeForRecording(recordingId: String): Flow<List<ExtractedItem>>

    @Query("SELECT * FROM extracted_items WHERE type = :type ORDER BY createdAt DESC")
    fun observeByType(type: ItemType): Flow<List<ExtractedItem>>

    @Query("SELECT * FROM extracted_items WHERE type = 'TODO' ORDER BY isCompleted ASC, deadline ASC")
    fun observeAllTodos(): Flow<List<ExtractedItem>>

    // 同 RecordingDao.searchRecordings:LIKE 取代 FTS MATCH 以支持中文搜索。
    @Query("""
        SELECT * FROM extracted_items
        WHERE content LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun search(query: String): Flow<List<ExtractedItem>>

    @Query("SELECT COUNT(*) FROM extracted_items WHERE recordingId = :recordingId AND type = :type")
    suspend fun countOfType(recordingId: String, type: ItemType): Int
}
