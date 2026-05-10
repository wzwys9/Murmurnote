package app.murmurnote.android.domain.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ProcessingQueueStatus { WAITING, RUNNING, COMPLETED, FAILED, CANCELLED }

data class ProcessingQueueEntry(
    val queueId: String,
    val recordingId: String?,
    val fileName: String,
    val status: ProcessingQueueStatus,
    val detail: String = "",
    val errorMessage: String? = null
)

@Singleton
class ProcessingQueueTracker @Inject constructor() {
    private val _entries = MutableStateFlow<List<ProcessingQueueEntry>>(emptyList())
    val entries: StateFlow<List<ProcessingQueueEntry>> = _entries.asStateFlow()

    fun enqueue(entry: ProcessingQueueEntry) {
        _entries.update { old ->
            old.filterNot { it.queueId == entry.queueId } + entry
        }
    }

    fun markRunning(queueId: String, detail: String) {
        update(queueId) { it.copy(status = ProcessingQueueStatus.RUNNING, detail = detail, errorMessage = null) }
    }

    fun updateDetail(queueId: String, detail: String) {
        update(queueId) { it.copy(detail = detail) }
    }

    fun markCompleted(queueId: String) {
        update(queueId) { it.copy(status = ProcessingQueueStatus.COMPLETED, detail = "处理完成", errorMessage = null) }
    }

    fun markFailed(queueId: String, errorMessage: String) {
        update(queueId) { it.copy(status = ProcessingQueueStatus.FAILED, detail = "处理失败", errorMessage = errorMessage) }
    }

    fun markCancelled(queueId: String) {
        update(queueId) { it.copy(status = ProcessingQueueStatus.CANCELLED, detail = "已取消", errorMessage = null) }
    }

    fun pruneFinished(keepLast: Int = 3) {
        _entries.update { entries ->
            val finished = entries.filter {
                it.status == ProcessingQueueStatus.COMPLETED ||
                    it.status == ProcessingQueueStatus.FAILED ||
                    it.status == ProcessingQueueStatus.CANCELLED
            }.takeLast(keepLast)
            val active = entries.filter {
                it.status == ProcessingQueueStatus.WAITING ||
                    it.status == ProcessingQueueStatus.RUNNING
            }
            active + finished
        }
    }

    private fun update(queueId: String, block: (ProcessingQueueEntry) -> ProcessingQueueEntry) {
        _entries.update { entries ->
            entries.map { if (it.queueId == queueId) block(it) else it }
        }
    }
}
