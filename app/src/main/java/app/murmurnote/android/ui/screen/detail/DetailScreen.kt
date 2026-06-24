package app.murmurnote.android.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.RecordingSegmentStatus
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.util.formatDurationMs
import app.murmurnote.android.util.formatTimestampFull

@Composable
fun DetailScreen(
    recordingId: String,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    LaunchedEffect(recordingId) { viewModel.load(recordingId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.recording?.title ?: "录音详情", maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(); onBack() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 播放器
            item {
                PlayerCard(
                    isPlaying = state.isPlaying,
                    durationMs = state.durationMs,
                    positionMs = state.positionMs,
                    speed = state.speed,
                    onTogglePlay = viewModel::togglePlay,
                    onSeek = { viewModel.seekTo(it) },
                    onSpeed = { viewModel.setSpeed(it) }
                )
            }

            state.recording?.let { rec ->
                item {
                    TagsArchiveCard(
                        tags = rec.tags.toTagList(),
                        archived = rec.archived,
                        draft = state.tagDraft,
                        error = state.tagError,
                        onDraftChange = viewModel::updateTagDraft,
                        onAdd = viewModel::addTag,
                        onRemove = viewModel::removeTag,
                        onToggleArchived = viewModel::toggleArchived
                    )
                }
            }

            // 重试卡片：转写失败 / 提取失败 / completed 但无内容（典型：Ollama 503 被吞）
            if (state.canReprocess || state.reprocessError != null ||
                state.recording?.processingStatus?.let {
                    it != ProcessingStatus.COMPLETED && it != ProcessingStatus.FAILED
                } == true
            ) {
                item {
                    ReprocessCard(
                        status = state.recording?.processingStatus,
                        errorMessage = state.recording?.errorMessage,
                        canReprocess = state.canReprocess,
                        reprocessing = state.reprocessing,
                        reprocessError = state.reprocessError,
                        onRetry = { viewModel.reprocess(context) },
                        onDismissError = viewModel::clearReprocessError
                    )
                }
            }

            if (state.recordingSegments.isNotEmpty()) {
                item {
                    RecordingSegmentsCard(segments = state.recordingSegments)
                }
            }

            // AI 总结：只有存在总结/草稿或提取失败文案时展示。纯转写模式下保持详情页干净。
            state.recording?.let { rec ->
                // 处理中（还没到 EXTRACTING 完成）的录音不显示这张卡，避免和 ReprocessCard 同时存在让人困惑
                val hasSummary = !rec.finalSummary.isNullOrBlank() ||
                    !rec.summary.isNullOrBlank() ||
                    !rec.draftSummary.isNullOrBlank()
                val showSummaryCard = hasSummary && (
                    rec.processingStatus == ProcessingStatus.COMPLETED ||
                        rec.processingStatus == ProcessingStatus.FAILED
                    )
                if (showSummaryCard) {
                    item {
                        SummaryCard(
                            summary = rec.finalSummary ?: rec.summary,
                            draftSummary = rec.draftSummary,
                            createdAt = rec.createdAt,
                            regenerating = state.regeneratingSummary,
                            regenerateError = state.regenerateError,
                            canRegenerate = !rec.rawTranscript.isNullOrBlank(),
                            onRegenerate = viewModel::regenerateSummary,
                            onDismissError = viewModel::clearRegenerateError
                        )
                    }
                }
            }

            state.recording?.let { rec ->
                if (rec.processingStatus == ProcessingStatus.COMPLETED ||
                    rec.processingStatus == ProcessingStatus.FAILED
                ) {
                    item {
                        ExportCard(
                            exportError = state.exportError,
                            onExport = { format -> viewModel.exportResult(context, format) },
                            onDismissError = viewModel::clearExportError
                        )
                    }
                }
            }

            // 4 类提取
            val sections = listOf(
                ItemType.TODO to "✅ 待办",
                ItemType.IDEA to "💡 创意",
                ItemType.NOTE to "📌 备忘",
                ItemType.DECISION to "🎯 决策"
            )
            sections.forEach { (type, label) ->
                val items = state.items.filter { it.type == type }
                if (items.isNotEmpty()) {
                    item(key = "section-${type.name}") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "$label (${items.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                items.forEach { it2 ->
                                    Box(modifier = Modifier.padding(vertical = 2.dp).fillMaxWidth()) {
                                        Text(
                                            formatTimestampFull(it2.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                                        ) {
                                            if (type == ItemType.TODO) {
                                                Checkbox(
                                                    checked = it2.isCompleted,
                                                    onCheckedChange = { v -> viewModel.toggleCompleted(it2.id, v) }
                                                )
                                            } else {
                                                Text("• ", style = MaterialTheme.typography.bodyLarge)
                                            }
                                            Text(
                                                it2.content,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 转写
            if (state.segments.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            val rec = state.recording
                            rec?.let {
                                Text(
                                    formatTimestampFull(rec.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                                Text("完整转写", style = MaterialTheme.typography.titleSmall)
                                if (rec?.transcriptDirty == true) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "转写已修改，重新生成总结会使用修正后的文本。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    TextButton(
                                        onClick = viewModel::regenerateSummary,
                                        enabled = !state.regeneratingSummary
                                    ) {
                                        Icon(
                                            Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(if (state.regeneratingSummary) "生成中…" else "重新生成总结")
                                    }
                                }
                                state.segmentEditError?.let { error ->
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            error,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(onClick = viewModel::clearSegmentEditError) {
                                            Icon(Icons.Filled.Close, contentDescription = "忽略")
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                state.segments.forEach { seg ->
                                    TranscriptSegmentRow(
                                        segment = seg,
                                        editing = state.editingSegmentId == seg.id,
                                        draft = state.segmentDraft,
                                        saving = state.savingSegment,
                                        onSeek = { viewModel.seekTo(seg.startMs) },
                                        onEdit = { viewModel.startEditingSegment(seg) },
                                        onDraftChange = viewModel::updateSegmentDraft,
                                        onSave = viewModel::saveSegmentEdit,
                                        onCancel = viewModel::cancelSegmentEdit
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    exportError: String?,
    onExport: (String) -> Unit,
    onDismissError: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "导出",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("md" to "Markdown", "txt" to "TXT", "json" to "JSON").forEach { (format, label) ->
                    Button(onClick = { onExport(format) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(label)
                    }
                }
            }
            exportError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Filled.Close, contentDescription = "忽略")
                    }
                }
            }
        }
    }
}

@Composable
private fun TagsArchiveCard(
    tags: List<String>,
    archived: Boolean,
    draft: String,
    error: String?,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onToggleArchived: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "标签和归档",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onToggleArchived) {
                    Text(if (archived) "取消归档" else "归档")
                }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemove(tag) },
                            label = { Text("$tag ×") }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("添加自定义标签") }
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onAdd) {
                    Text("添加")
                }
            }
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (archived) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已归档的录音默认从列表隐藏，可在列表页切换显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegment,
    editing: Boolean,
    draft: String,
    saving: Boolean,
    onSeek: () -> Unit,
    onEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatDurationMs(segment.startMs)}-${formatDurationMs(segment.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (segment.isEdited) {
                Text(
                    "已编辑",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(4.dp))
            }
            if (editing) {
                IconButton(onClick = onSave, enabled = !saving) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = "保存")
                    }
                }
                IconButton(onClick = onCancel, enabled = !saving) {
                    Icon(Icons.Filled.Close, contentDescription = "取消")
                }
            } else {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑转写")
                }
            }
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        } else {
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek() }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun RecordingSegmentsCard(segments: List<RecordingSegment>) {
    val done = segments.count { it.status == RecordingSegmentStatus.TRANSCRIBED }
    val failed = segments.count { it.status == RecordingSegmentStatus.FAILED }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "录音片段 $done/${segments.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (failed > 0) "$failed 段失败，最终处理会补跑缺失转写。"
                else "用于录音中实时转写和最终处理复用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            segments.forEach { segment ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "${segment.sequence + 1}. ${formatDurationMs(segment.startMs)}-${formatDurationMs(segment.endMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        recordingSegmentStatusLabel(segment.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = recordingSegmentStatusColor(segment.status)
                    )
                }
                segment.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(
    isPlaying: Boolean,
    durationMs: Int,
    positionMs: Int,
    speed: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit
) {
    // 拖动 Slider 时用本地状态，避免被 ticker 抢回。释放后才提交 seek。
    val dragValueState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val drag = dragValueState.value
    val displayedFraction = drag
        ?: if (durationMs > 0) positionMs / durationMs.toFloat() else 0f
    val displayedPositionMs = if (drag != null && durationMs > 0)
        (drag * durationMs).toLong() else positionMs.toLong()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, enabled = durationMs > 0) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Slider(
                        value = displayedFraction.coerceIn(0f, 1f),
                        enabled = durationMs > 0,
                        onValueChange = { v -> dragValueState.value = v },
                        onValueChangeFinished = {
                            dragValueState.value?.let { v ->
                                if (durationMs > 0) onSeek((v * durationMs).toLong())
                            }
                            dragValueState.value = null
                        }
                    )
                    Text(
                        "${formatDurationMs(displayedPositionMs)} / ${formatDurationMs(durationMs.toLong())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { sp ->
                    FilterChip(
                        selected = speed == sp,
                        onClick = { onSpeed(sp) },
                        label = { Text("${sp}x") }
                    )
                }
            }
        }
    }
}

/**
 * 处理状态 / 重试卡片：
 * - 处理中：显示当前阶段（PENDING/CONVERTING/...） + 旋转指示器，不出按钮
 * - FAILED：红色提示 + errorMessage + 「重新处理」按钮
 * - COMPLETED 但内容为空：黄色提示 + 「重新处理」按钮（用于 Ollama 503 被吞这类软失败）
 */
@Composable
private fun ReprocessCard(
    status: ProcessingStatus?,
    errorMessage: String?,
    canReprocess: Boolean,
    reprocessing: Boolean,
    reprocessError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit
) {
    val (title, body, accent) = when {
        status == ProcessingStatus.FAILED -> Triple(
            "⚠ 处理失败",
            errorMessage?.takeIf { it.isNotBlank() } ?: "上一次处理未完成。",
            MaterialTheme.colorScheme.error
        )
        canReprocess -> Triple(
            "ℹ 转写或 AI 提取无内容",
            "可能是网络抖动 / Ollama 服务忙 / 录音过短。点重试再跑一次。",
            Color(0xFFE08300)
        )
        status != null && status != ProcessingStatus.COMPLETED -> Triple(
            "处理中…",
            "当前阶段：${labelOf(status)}",
            MaterialTheme.colorScheme.primary
        )
        else -> Triple("", "", MaterialTheme.colorScheme.primary)
    }
    if (title.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)

            // 进行中（非失败、非完成）：显示进度指示而不是按钮
            if (status != null && status != ProcessingStatus.COMPLETED &&
                status != ProcessingStatus.FAILED && !canReprocess
            ) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("请稍候，前台 Service 正在处理", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry, enabled = !reprocessing) {
                    if (reprocessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("启动中…")
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("重新处理")
                    }
                }
            }

            reprocessError?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Filled.Delete, contentDescription = "忽略", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * AI 总结卡片：
 * - 有内容：直接显示 + 右上角小刷新按钮（让满意度低的用户也能再 roll 一次）
 * - 空 / "（提取失败：...）" fallback：突出"未提取到要点"，给一个大的「重新生成总结」按钮
 * 仅重跑 LLM 提取，不动 ASR；前提是 rawTranscript 非空（否则禁用按钮）。
 */
@Composable
private fun SummaryCard(
    summary: String?,
    draftSummary: String?,
    createdAt: Long,
    regenerating: Boolean,
    regenerateError: String?,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDismissError: () -> Unit
) {
    val isEmptyOrFallback = summary.isNullOrBlank() || summary.contains("提取失败")

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                formatTimestampFull(createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                draftSummary?.takeIf { it.isNotBlank() && it != summary }?.let {
                    Text(
                        "录音中临时总结",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📝 AI 总结",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onRegenerate,
                        enabled = canRegenerate && !regenerating
                    ) {
                        if (regenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "重新生成总结",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (isEmptyOrFallback) {
                    Text(
                        summary?.takeIf { it.isNotBlank() } ?: "AI 没能从这段录音提取到要点。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRegenerate,
                        enabled = canRegenerate && !regenerating
                    ) {
                        if (regenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("生成中…")
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("重新生成总结")
                        }
                    }
                    if (!canRegenerate) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "尚无转写文本，先完成 ASR 转写。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(summary!!, style = MaterialTheme.typography.bodyLarge)
                }

                regenerateError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "✗ 重新生成失败：$err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        IconButton(onClick = onDismissError) {
                            Icon(Icons.Filled.Delete, contentDescription = "忽略", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun recordingSegmentStatusColor(status: RecordingSegmentStatus): Color = when (status) {
    RecordingSegmentStatus.READY -> MaterialTheme.colorScheme.onSurfaceVariant
    RecordingSegmentStatus.TRANSCRIBING -> MaterialTheme.colorScheme.primary
    RecordingSegmentStatus.TRANSCRIBED -> Color(0xFF2E7D32)
    RecordingSegmentStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun recordingSegmentStatusLabel(status: RecordingSegmentStatus): String = when (status) {
    RecordingSegmentStatus.READY -> "等待"
    RecordingSegmentStatus.TRANSCRIBING -> "转写中"
    RecordingSegmentStatus.TRANSCRIBED -> "已完成"
    RecordingSegmentStatus.FAILED -> "失败"
}

private fun labelOf(s: ProcessingStatus): String = when (s) {
    ProcessingStatus.PENDING -> "排队中"
    ProcessingStatus.RECORDING -> "录音中"
    ProcessingStatus.CONVERTING -> "转码 mono WAV"
    ProcessingStatus.SPLITTING -> "切分静音段"
    ProcessingStatus.TRANSCRIBING -> "GLM-ASR 转写"
    ProcessingStatus.EXTRACTING -> "Ollama AI 提取"
    ProcessingStatus.COMPLETED -> "已完成"
    ProcessingStatus.FAILED -> "失败"
}

private fun String.toTagList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
