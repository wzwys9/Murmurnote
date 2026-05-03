package app.murmurnote.android.data.repository

import app.murmurnote.android.data.local.dao.ApiLogDao
import app.murmurnote.android.data.local.entity.ApiLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiLogRepository @Inject constructor(
    private val dao: ApiLogDao
) {
    fun observeRecent(limit: Int = 50): Flow<List<ApiLog>> = dao.observeRecent(limit)
    suspend fun getRecent(limit: Int = 50): List<ApiLog> = dao.getRecent(limit)
    suspend fun clear() = dao.clear()
}
