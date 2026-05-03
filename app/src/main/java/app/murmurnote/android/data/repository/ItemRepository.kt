package app.murmurnote.android.data.repository

import app.murmurnote.android.data.local.dao.ItemDao
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItemRepository @Inject constructor(
    private val itemDao: ItemDao
) {
    suspend fun insert(item: ExtractedItem): Long = itemDao.insert(item)
    suspend fun insertAll(items: List<ExtractedItem>) = itemDao.insertAll(items)
    suspend fun setCompleted(id: Long, completed: Boolean) = itemDao.setCompleted(id, completed)
    suspend fun delete(id: Long) = itemDao.deleteById(id)
    suspend fun deleteForRecording(recordingId: String) = itemDao.deleteForRecording(recordingId)

    fun observeForRecording(id: String): Flow<List<ExtractedItem>> = itemDao.observeForRecording(id)
    fun observeByType(type: ItemType): Flow<List<ExtractedItem>> = itemDao.observeByType(type)
    fun observeAllTodos(): Flow<List<ExtractedItem>> = itemDao.observeAllTodos()
    fun search(query: String): Flow<List<ExtractedItem>> = itemDao.search(query)
}
