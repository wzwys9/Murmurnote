package app.murmurnote.android.ui.screen.detail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.BuildConfig
import app.murmurnote.android.audio.AudioPlayer
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.data.repository.ItemRepository
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
        val recordingSegments: List<RecordingSegment> = emptyList(),
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
        val regenerateError: String? = null,
        val exportError: String? = null,
        val editingSegmentId: Long? = null,
        val segmentDraft: String = "",
        val savingSegment: Boolean = false,
        val segmentEditError: String? = null,
        val tagDraft: String = "",
        val tagError: String? = null
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
            recordingRepo.observeRecordingSegments(id).collect { segs ->
                _state.update { it.copy(recordingSegments = segs) }
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

    fun updateTagDraft(text: String) {
        _state.update { it.copy(tagDraft = text.take(24), tagError = null) }
    }

    fun addTag() {
        val rec = _state.value.recording ?: return
        val tag = normalizeTag(_state.value.tagDraft)
        if (tag == null) {
            _state.update { it.copy(tagError = "请输入标签名称") }
            return
        }
        val tags = (rec.tagList() + tag).distinct()
        viewModelScope.launch {
            recordingRepo.updateTags(rec.id, tags)
            _state.update { it.copy(tagDraft = "", tagError = null) }
        }
    }

    fun removeTag(tag: String) {
        val rec = _state.value.recording ?: return
        viewModelScope.launch {
            recordingRepo.updateTags(rec.id, rec.tagList().filterNot { it == tag })
        }
    }

    fun toggleArchived() {
        val rec = _state.value.recording ?: return
        viewModelScope.launch {
            recordingRepo.updateArchived(rec.id, !rec.archived)
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
                    recordingRepo.update(
                        rec.copy(
                            title = finalTitle,
                            summary = ext.summary,
                            finalSummary = ext.summary,
                            transcriptDirty = false
                        )
                    )
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

    fun startEditingSegment(segment: TranscriptSegment) {
        _state.update {
            it.copy(
                editingSegmentId = segment.id,
                segmentDraft = segment.text,
                segmentEditError = null
            )
        }
    }

    fun updateSegmentDraft(text: String) {
        _state.update { it.copy(segmentDraft = text) }
    }

    fun cancelSegmentEdit() {
        _state.update {
            it.copy(
                editingSegmentId = null,
                segmentDraft = "",
                savingSegment = false,
                segmentEditError = null
            )
        }
    }

    fun saveSegmentEdit() {
        val rec = _state.value.recording ?: return
        val segmentId = _state.value.editingSegmentId ?: return
        val text = _state.value.segmentDraft.trim()
        if (text.isBlank()) {
            _state.update { it.copy(segmentEditError = "转写段不能为空") }
            return
        }
        if (_state.value.savingSegment) return
        viewModelScope.launch {
            _state.update { it.copy(savingSegment = true, segmentEditError = null) }
            runCatching {
                recordingRepo.updateTranscriptSegmentText(rec.id, segmentId, text)
            }.onSuccess {
                _state.update {
                    it.copy(
                        editingSegmentId = null,
                        segmentDraft = "",
                        savingSegment = false
                    )
                }
                logger.i("Detail", "segment edited id=${rec.id} segmentId=$segmentId chars=${text.length}")
            }.onFailure { e ->
                logger.e("Detail", "saveSegmentEdit failed", e)
                _state.update {
                    it.copy(
                        savingSegment = false,
                        segmentEditError = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun clearSegmentEditError() {
        _state.update { it.copy(segmentEditError = null) }
    }

    fun exportResult(context: Context, format: String) {
        val rec = _state.value.recording ?: return
        val segments = _state.value.segments
        val items = _state.value.items
        viewModelScope.launch {
            runCatching {
                val safeTitle = rec.title.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]+"), "_").take(40)
                    .ifBlank { "murmurnote" }
                val extension = format.lowercase()
                val mime = when (extension) {
                    "json" -> "application/json"
                    "txt" -> "text/plain"
                    else -> "text/markdown"
                }
                val body = when (extension) {
                    "json" -> buildJsonExport(rec, segments, items)
                    "txt" -> buildTextExport(rec, segments, items)
                    else -> buildMarkdownExport(rec, segments, items)
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "result_share").apply { mkdirs() }
                    dir.listFiles()?.forEach { if (it.name.startsWith("murmurnote_")) it.delete() }
                    File(dir, "murmurnote_${safeTitle}_${rec.id.take(8)}.$extension").also {
                        it.writeText(body, Charsets.UTF_8)
                    }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    file
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, rec.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(sendIntent, "导出录音结果"))
            }.onFailure { e ->
                logger.e("Detail", "exportResult failed", e)
                _state.update { it.copy(exportError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun clearExportError() {
        _state.update { it.copy(exportError = null) }
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

    private fun Recording.tagList(): List<String> =
        tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun normalizeTag(input: String): String? =
        input.trim().replace(",", " ").take(24).takeIf { it.isNotBlank() }

    private fun buildMarkdownExport(
        rec: Recording,
        segments: List<TranscriptSegment>,
        items: List<ExtractedItem>
    ): String = buildString {
        appendLine("# ${rec.title}")
        appendLine()
        appendLine("- 时间：${formatPretty(rec.createdAt)}")
        appendLine("- 时长：${rec.durationMs} ms")
        appendLine("- 标签：${rec.tagList().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "无"}")
        appendLine("- 归档：${if (rec.archived) "是" else "否"}")
        appendLine()
        appendLine("## AI 总结")
        appendLine()
        appendLine(rec.finalSummary ?: rec.summary ?: "（无）")
        appendLine()
        appendLine("## 提取项")
        ItemType.entries.forEach { type ->
            val grouped = items.filter { it.type == type }
            if (grouped.isNotEmpty()) {
                appendLine()
                appendLine("### ${type.name.lowercase()}")
                grouped.forEach { appendLine("- ${it.content}") }
            }
        }
        appendLine()
        appendLine("## 完整转写")
        val sortedSegments = segments.sortedBy { it.sequence }
        if (sortedSegments.isNotEmpty()) {
            sortedSegments.forEach {
                appendLine()
                appendLine("[${formatDurationForExport(it.startMs)}-${formatDurationForExport(it.endMs)}] ${it.text}")
            }
        } else {
            appendLine()
            appendLine(rec.rawTranscript ?: "（无）")
        }
    }

    private fun buildTextExport(
        rec: Recording,
        segments: List<TranscriptSegment>,
        items: List<ExtractedItem>
    ): String = buildString {
        appendLine(rec.title)
        appendLine(formatPretty(rec.createdAt))
        appendLine("标签：${rec.tagList().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "无"}")
        appendLine("归档：${if (rec.archived) "是" else "否"}")
        appendLine()
        appendLine("AI 总结")
        appendLine(rec.finalSummary ?: rec.summary ?: "（无）")
        appendLine()
        appendLine("提取项")
        items.forEach { appendLine("[${it.type.name.lowercase()}] ${it.content}") }
        appendLine()
        appendLine("完整转写")
        val sortedSegments = segments.sortedBy { it.sequence }
        if (sortedSegments.isNotEmpty()) {
            sortedSegments.forEach {
                appendLine("[${formatDurationForExport(it.startMs)}-${formatDurationForExport(it.endMs)}] ${it.text}")
            }
        } else {
            appendLine(rec.rawTranscript ?: "（无）")
        }
    }

    private fun buildJsonExport(
        rec: Recording,
        segments: List<TranscriptSegment>,
        items: List<ExtractedItem>
    ): String {
        val root = JSONObject()
            .put("id", rec.id)
            .put("title", rec.title)
            .put("createdAt", rec.createdAt)
            .put("durationMs", rec.durationMs)
            .put("source", rec.source.name)
            .put("summary", rec.finalSummary ?: rec.summary)
            .put("draftSummary", rec.draftSummary)
            .put("rawTranscript", rec.rawTranscript)
            .put("tags", JSONArray().also { array -> rec.tagList().forEach { array.put(it) } })
            .put("archived", rec.archived)
        root.put("items", JSONArray().also { array ->
            items.forEach {
                array.put(
                    JSONObject()
                        .put("type", it.type.name.lowercase())
                        .put("content", it.content)
                        .put("deadline", it.deadline)
                        .put("isCompleted", it.isCompleted)
                        .put("sourceTimestampMs", it.sourceTimestampMs)
                )
            }
        })
        root.put("segments", JSONArray().also { array ->
            segments.sortedBy { it.sequence }.forEach {
                array.put(
                    JSONObject()
                        .put("sequence", it.sequence)
                        .put("startMs", it.startMs)
                        .put("endMs", it.endMs)
                        .put("text", it.text)
                )
            }
        })
        return root.toString(2)
    }

    private fun formatDurationForExport(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.US, minutes, seconds)
        }
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
