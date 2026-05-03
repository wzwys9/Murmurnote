package app.murmurnote.android.domain.usecase

import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.repository.ItemRepository
import app.murmurnote.android.data.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

data class SearchResult(
    val recordings: List<Recording>,
    val items: List<ExtractedItem>
)

class SearchUseCase @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val itemRepository: ItemRepository
) {
    operator fun invoke(query: String): Flow<SearchResult> {
        // DAO 已切到 LIKE 实现:这里只做最小预处理(trim),不再加 FTS 的 "*" 前缀符,
        // 也不按空格切词——LIKE 直接 substring 匹配,中英文 / 混合输入都能命中。
        val q = query.trim()
        val recordings = recordingRepository.search(q)
        val items = itemRepository.search(q)
        return combine(recordings, items) { r, i -> SearchResult(r, i) }
    }
}
