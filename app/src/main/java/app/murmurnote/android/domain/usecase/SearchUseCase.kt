package app.murmurnote.android.domain.usecase

import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.repository.ItemRepository
import app.murmurnote.android.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar
import javax.inject.Inject

data class SearchResult(
    val recordings: List<Recording>,
    val items: List<ExtractedItem>
)

enum class SearchScope { ALL, SUMMARY, TRANSCRIPT, ITEMS }

enum class SearchDateRange { ALL, TODAY, SEVEN_DAYS, THIRTY_DAYS }

data class SearchFilters(
    val scope: SearchScope = SearchScope.ALL,
    val dateRange: SearchDateRange = SearchDateRange.ALL,
    val itemType: ItemType? = null
)

class SearchUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val itemRepository: ItemRepository
) {
    operator fun invoke(query: String, filters: SearchFilters = SearchFilters()): Flow<SearchResult> {
        // DAO 已切到 LIKE 实现:这里只做最小预处理(trim),不再加 FTS 的 "*" 前缀符,
        // 也不按空格切词——LIKE 直接 substring 匹配,中英文 / 混合输入都能命中。
        val q = query.trim()
        val (fromMs, toMs) = filters.dateRange.bounds()
        val searchSummary = filters.scope == SearchScope.ALL || filters.scope == SearchScope.SUMMARY
        val searchTranscript = filters.scope == SearchScope.ALL || filters.scope == SearchScope.TRANSCRIPT
        val searchItems = filters.scope == SearchScope.ALL || filters.scope == SearchScope.ITEMS
        val recordings = if (searchSummary || searchTranscript) {
            recordingRepository.searchFiltered(
                query = q,
                fromMs = fromMs,
                toMs = toMs,
                searchSummary = searchSummary,
                searchTranscript = searchTranscript
            )
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val items = if (searchItems) {
            itemRepository.searchFiltered(q, fromMs, toMs, filters.itemType)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return combine(recordings, items) { r, i -> SearchResult(r, i) }
    }

    private fun SearchDateRange.bounds(): Pair<Long?, Long?> {
        val now = System.currentTimeMillis()
        val from = when (this) {
            SearchDateRange.ALL -> null
            SearchDateRange.TODAY -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            SearchDateRange.SEVEN_DAYS -> now - 7L * 24 * 60 * 60 * 1000
            SearchDateRange.THIRTY_DAYS -> now - 30L * 24 * 60 * 60 * 1000
        }
        return from to now
    }
}
