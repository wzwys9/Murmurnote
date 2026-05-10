package app.murmurnote.android.ui.screen.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.audio.AudioPlayer
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.data.repository.ItemRepository
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val recordingRepo: RecordingRepository,
    private val itemRepo: ItemRepository,
    private val player: AudioPlayer,
    private val llmClient: LlmClient,
    private val logger: Logger
) : ViewModel() {

    data class UiState(
        val recording: Recording? = null,
        val segments: List<TranscriptSegment> = emptyList(),
        val items: List<ExtractedItem> = emptyList(),
        val isPlaying: Boolean = false,
        val durationMs: Int = 0,
        val positionMs: Int = 0,
        val speed: Float = 1.0f,
        val reprocessing: Boolean = false,
        val reprocessError: String? = null,
        // "重新生成总结"路径：只重跑 LLM 提取，不重跑 ASR；用于 Ollama 临时挂掉 / LLM 返回空 summary
        // 这种"ASR 已成功但提取失败/为空"的常见场景。
        val regeneratingSummary: Boolean = false,
        val regenerateError: String? = null
    ) {
        /**
         * 是否需要给用户显示「重试」按钮：
         * - FAILED 状态：转写或提取阶段崩了
         * - COMPLETED 但既无转写段落又无 items（典型场景：Ollama 503 被吞、或 ASR 段返空）
         */
        val canReprocess: Boolean
            get() {
                val r = recording ?: return false
                if (r.processingStatus == ProcessingStatus.FAILED) return true
                if (r.processingStatus == ProcessingStatus.COMPLETED &&
                    segments.isEmpty() && items.isEmpty()
                ) return true
                return false
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var loadedPath: String? = null
    private var loadedId: String? = null
    private var positionTicker: Job? = null

    fun load(id: String) {
        if (loadedId == id) return
        loadedId = id

        viewModelScope.launch {
            recordingRepo.observe(id).collect { rec ->
                _state.update { it.copy(recording = rec) }
                val path = rec?.originalFilePath
                if (path != null && path != loadedPath) {
                    val f = File(path)
                    if (f.exists()) {
                        loadedPath = path
                        // 注意：load() 不会自动 start —— AudioPlayer 修复后只在 pendingPlay=true 时才 play
                        player.load(f) { dur ->
                            _state.update { it.copy(durationMs = dur, positionMs = 0, isPlaying = false) }
                        }
                        player.setOnComplete {
                            stopTicker()
                            _state.update { it.copy(isPlaying = false, positionMs = 0) }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            recordingRepo.observeSegments(id).collect { segs ->
                _state.update { it.copy(segments = segs) }
            }
        }
        viewModelScope.launch {
            itemRepo.observeForRecording(id).collect { items ->
                _state.update { it.copy(items = items) }
            }
        }
    }

    fun togglePlay() {
        val expectedPlaying = !_state.value.isPlaying
        if (expectedPlaying) {
            player.play()
            _state.update { it.copy(isPlaying = true) }
            startTicker()
        } else {
            player.pause()
            _state.update { it.copy(isPlaying = false) }
            stopTicker()
        }
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
        _state.update { it.copy(positionMs = ms.toInt()) }
    }

    fun setSpeed(s: Float) {
        player.setSpeed(s)
        _state.update { it.copy(speed = s) }
    }

    fun toggleCompleted(itemId: Long, done: Boolean) {
        viewModelScope.launch { itemRepo.setCompleted(itemId, done) }
    }

    fun delete() {
        viewModelScope.launch {
            _state.value.recording?.id?.let { recordingRepo.delete(it) }
        }
    }

    /**
     * 重新跑 Pipeline。复用现有 Recording 行（id 不变），原 segments/items 会被清空后重新生成。
     * 失败原因写到 reprocessError 给 UI 提示。成功则进入前台 Service —— UI 仍可看到该 recording
     * 的状态从 PENDING 流到 COMPLETED（observeById 持续订阅）。
     */
    fun reprocess(context: Context) {
        val rec = _state.value.recording ?: return
        if (_state.value.reprocessing) return
        viewModelScope.launch {
            _state.update { it.copy(reprocessing = true, reprocessError = null) }
            val source = rec.source
            val file = File(rec.originalFilePath)
            if (!file.exists()) {
                logger.w("Detail", "reprocess aborted: file missing ${rec.originalFilePath}")
                _state.update {
                    it.copy(
                        reprocessing = false,
                        reprocessError = "原始音频文件已不存在，无法重试"
                    )
                }
                return@launch
            }
            // 释放当前 player 引用，避免重处理过程中文件被占用造成 ASR 段失败
            player.release()
            logger.i("Detail", "reprocess id=${rec.id} src=${rec.originalFilePath}")
            val intent = TranscriptionService.reprocessIntent(context, file, source, rec.id)
            // O+ 必须用 startForegroundService（service 自己会调 startForeground）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _state.update { it.copy(reprocessing = false) }
        }
    }

    fun clearReprocessError() {
        _state.update { it.copy(reprocessError = null) }
    }

    /**
     * 仅重新跑一次 Ollama 提取，不动 ASR。前提：Recording.rawTranscript 非空（即转写已经成功）。
     * 适用场景：上次提取因为 LLM 临时不可用 / 模型不存在 / 返回空 summary 等被吞掉，
     * 用户明知转写文字是有效的，只想再让 LLM 试一次。比走整条 Pipeline 重跑要快、不消耗 ASR 配额。
     */
    fun regenerateSummary() {
        val rec = _state.value.recording ?: return
        val transcript = rec.rawTranscript
        if (transcript.isNullOrBlank()) {
            _state.update { it.copy(regenerateError = "尚无转写文本，无法重新生成总结。请先完成 ASR 转写。") }
            logger.w("Detail", "regenerateSummary aborted: rawTranscript blank")
            return
        }
        if (_state.value.regeneratingSummary) return
        viewModelScope.launch {
            _state.update { it.copy(regeneratingSummary = true, regenerateError = null) }
            logger.i("Detail", "regenerateSummary id=${rec.id} chars=${transcript.length}")
            llmClient.extractItems(transcript).fold(
                onSuccess = { ext ->
                    val createdAtPretty = formatPretty(rec.createdAt)
                    // 旧 items 删干净再插，不然每次重试都会叠出重复条目
                    itemRepo.deleteForRecording(rec.id)
                    val newItems = ext.items.map { dto ->
                        ExtractedItem(
                            recordingId = rec.id,
                            type = mapItemType(dto.type),
                            content = dto.content,
                            deadline = dto.deadline?.let { parseDeadline(it) },
                            sourceTimestampMs = dto.sourceTimestampMs,
                            createdAt = rec.createdAt
                        )
                    }
                    itemRepo.insertAll(newItems)
                    val titleFromSummary = ext.summary
                        .lineSequence()
                        .map { it.trim().removePrefix("•").trim() }
                        .firstOrNull { it.isNotBlank() }
                        ?.take(30)
                    val finalTitle = if (titleFromSummary != null) {
                        "$titleFromSummary · $createdAtPretty"
                    } else {
                        "录音 $createdAtPretty"
                    }
                    recordingRepo.update(rec.copy(title = finalTitle, summary = ext.summary))
                    _state.update { it.copy(regeneratingSummary = false) }
                    logger.i(
                        "Detail",
                        "regenerateSummary ok items=${newItems.size} chars=${ext.summary.length}"
                    )
                },
                onFailure = { e ->
                    logger.e("Detail", "regenerateSummary failed", e)
                    _state.update {
                        it.copy(
                            regeneratingSummary = false,
                            regenerateError = e.message?.takeIf { m -> m.isNotBlank() } ?: e.javaClass.simpleName
                        )
                    }
                }
            )
        }
    }

    fun clearRegenerateError() {
        _state.update { it.copy(regenerateError = null) }
    }

    private fun formatPretty(epochMs: Long): String =
        SimpleDateFormat("yyyy年MM月dd日 HH时mm分ss秒", Locale.US).format(Date(epochMs))

    private fun parseDeadline(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
    } catch (_: Exception) {
        null
    }

    private fun mapItemType(s: String): ItemType = when (s.lowercase()) {
        "todo" -> ItemType.TODO
        "idea" -> ItemType.IDEA
        "note" -> ItemType.NOTE
        "decision" -> ItemType.DECISION
        else -> ItemType.NOTE
    }

    private fun startTicker() {
        stopTicker()
        positionTicker = viewModelScope.launch {
            while (true) {
                val pos = player.positionMs()
                val playing = player.isPlaying()
                _state.update { it.copy(positionMs = pos, isPlaying = playing) }
                if (!playing) break  // 自然结束或外部 pause
                delay(150)
            }
        }
    }

    private fun stopTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }

    override fun onCleared() {
        stopTicker()
        player.release()
        loadedPath = null
        loadedId = null
    }
}
