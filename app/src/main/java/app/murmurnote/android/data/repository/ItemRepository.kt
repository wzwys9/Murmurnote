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
    suspend fun setCompleted(id: Long, completed: Boolean) = itemDao.setCompleted(id, completed)

    fun observeForRecording(id: String): Flow<List<ExtractedItem>> = itemDao.observeForRecording(id)
    fun observeByType(type: ItemType): Flow<List<ExtractedItem>> = itemDao.observeByType(type)
    fun observeAllTodos(): Flow<List<ExtractedItem>> = itemDao.observeAllTodos()
    fun searchFiltered(
        query: String,
        fromMs: Long?,
        toMs: Long?,
        type: ItemType?
    ): Flow<List<ExtractedItem>> = itemDao.searchFiltered(query, fromMs, toMs, type)
}
