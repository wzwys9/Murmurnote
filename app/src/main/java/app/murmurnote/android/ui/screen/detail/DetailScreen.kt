package app.murmurnote.android.ui.screen.detail

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.RecordingSegmentStatus
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.ui.component.FirstLineAlignedCheckboxRow
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
    val locale = LocalConfiguration.current.locales[0]
    val itemSections = listOf(
        ItemType.TODO to stringResource(R.string.detail_items_todo),
        ItemType.IDEA to stringResource(R.string.detail_items_idea),
        ItemType.NOTE to stringResource(R.string.detail_items_note),
        ItemType.DECISION to stringResource(R.string.detail_items_decision)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.recording?.title ?: stringResource(R.string.detail_title), maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(); onBack() }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_delete))
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
                if (state.recording?.audioAvailable == false) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.detail_audio_cleaned),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
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
            }

            state.recording?.let { rec ->
                item {
                    TagsArchiveCard(
                        tags = rec.tags.toTagList(),
                        archived = rec.archived,
                        keepAudio = rec.keepAudio,
                        audioAvailable = rec.audioAvailable,
                        draft = state.tagDraft,
                        error = state.tagError,
                        onDraftChange = viewModel::updateTagDraft,
                        onAdd = viewModel::addTag,
                        onRemove = viewModel::removeTag,
                        onToggleArchived = viewModel::toggleArchived,
                        onToggleKeepAudio = viewModel::toggleKeepAudio
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
                val canOfferManualSummary = !rec.correctedTranscript.isNullOrBlank()
                val showSummaryCard = (hasSummary || canOfferManualSummary) &&
                    (rec.processingStatus == ProcessingStatus.COMPLETED ||
                        rec.processingStatus == ProcessingStatus.FAILED)
                if (showSummaryCard) {
                    item {
                        SummaryCard(
                            summary = rec.finalSummary ?: rec.summary,
                            draftSummary = rec.draftSummary,
                            summaryRevision = rec.summaryTranscriptRevision,
                            currentRevision = rec.correctionRevision,
                            isStale = hasSummary && (
                                rec.transcriptDirty ||
                                    rec.summaryTranscriptRevision != rec.correctionRevision
                                ),
                            createdAt = rec.createdAt,
                            regenerating = state.regeneratingSummary,
                            regenerateError = state.regenerateError,
                            canRegenerate = !rec.correctedTranscript.isNullOrBlank(),
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
            if (state.items.isNotEmpty() && state.recording?.transcriptDirty == true) {
                item(key = "stale-items-warning") {
                    Text(
                        stringResource(R.string.detail_items_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            itemSections.forEach { (type, label) ->
                val items = state.items.filter { it.type == type }
                if (items.isNotEmpty()) {
                    item(key = "section-${type.name}") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.detail_items_count, label, items.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(8.dp))
                                items.forEach { it2 ->
                                    Box(modifier = Modifier.padding(vertical = 2.dp).fillMaxWidth()) {
                                        Text(
                                            formatTimestampFull(it2.createdAt, locale),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(Alignment.TopEnd)
                                        )
                                        if (type == ItemType.TODO) {
                                            FirstLineAlignedCheckboxRow(
                                                checked = it2.isCompleted,
                                                onCheckedChange = { completed ->
                                                    viewModel.toggleCompleted(it2.id, completed)
                                                },
                                                text = it2.content,
                                                textStyle = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.Top,
                                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                                            ) {
                                                Text("• ", style = MaterialTheme.typography.bodyLarge)
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
            }

            // 转写
            if (state.segments.isNotEmpty() || state.recording?.correctedTranscript != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            val rec = state.recording
                            rec?.let {
                                Text(
                                    formatTimestampFull(rec.createdAt, locale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(
                                            if (state.showRawTranscript) R.string.detail_raw_transcript
                                            else R.string.detail_corrected_transcript
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = viewModel::toggleRawTranscript) {
                                        Text(
                                            stringResource(
                                                if (state.showRawTranscript) R.string.detail_back_to_corrected
                                                else R.string.detail_view_raw
                                            )
                                        )
                                    }
                                    if (!state.showRawTranscript && state.segments.any { it.rawText != it.correctedText }) {
                                        TextButton(onClick = viewModel::revertTranscriptToRaw) {
                                            Text(stringResource(R.string.detail_revert_raw))
                                        }
                                    }
                                }
                                if (rec?.transcriptDirty == true) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.detail_transcript_dirty),
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
                                        Text(
                                            stringResource(
                                                if (state.regeneratingSummary) R.string.detail_generating
                                                else R.string.detail_regenerate_summary
                                            )
                                        )
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
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.detail_dismiss),
                                            )
                                        }
                                    }
                                }
                                state.correctionActionMessage?.let { message ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val pendingRuleDiff = state.pendingRuleDiff
                                    if (pendingRuleDiff != null) {
                                        Text(
                                            stringResource(
                                                R.string.detail_exact_mapping,
                                                pendingRuleDiff.observedText,
                                                pendingRuleDiff.replacementText,
                                            ),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            stringResource(R.string.detail_dictionary_explanation),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = { viewModel.rememberPendingRule(global = false) },
                                                enabled = !state.savingRule
                                            ) {
                                                Text(stringResource(R.string.detail_remember_recording))
                                            }
                                            TextButton(
                                                onClick = { viewModel.rememberPendingRule(global = true) },
                                                enabled = !state.savingRule
                                            ) {
                                                Text(stringResource(R.string.detail_add_dictionary))
                                            }
                                            TextButton(
                                                onClick = viewModel::dismissCorrectionAction,
                                                enabled = !state.savingRule
                                            ) {
                                                Text(stringResource(R.string.detail_do_not_remember))
                                            }
                                        }
                                    } else if (state.lastCreatedRuleId != null) {
                                        TextButton(
                                            onClick = viewModel::undoLastRememberedRule,
                                            enabled = !state.savingRule
                                        ) {
                                            Text(stringResource(R.string.detail_undo_rule))
                                        }
                                    } else {
                                        TextButton(onClick = viewModel::dismissCorrectionAction) {
                                            Text(stringResource(R.string.action_got_it))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                if (state.segments.isEmpty()) {
                                    Text(
                                        text = if (state.showRawTranscript) {
                                            rec?.rawTranscript.orEmpty()
                                        } else {
                                            rec?.correctedTranscript.orEmpty()
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (state.showRawTranscript) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                } else {
                                    state.segments.forEach { seg ->
                                        TranscriptSegmentRow(
                                            segment = seg,
                                            showRaw = state.showRawTranscript,
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
                stringResource(R.string.detail_export),
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
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.detail_dismiss),
                        )
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
    keepAudio: Boolean,
    audioAvailable: Boolean,
    draft: String,
    error: String?,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onToggleArchived: () -> Unit,
    onToggleKeepAudio: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.detail_tags_archive),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onToggleArchived) {
                    Text(
                        stringResource(
                            if (archived) R.string.detail_unarchive else R.string.detail_archive
                        )
                    )
                }
                if (audioAvailable) {
                    TextButton(onClick = onToggleKeepAudio) {
                        Text(
                            stringResource(
                                if (keepAudio) R.string.detail_restore_cleanup
                                else R.string.detail_keep_audio
                            )
                        )
                    }
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
                    placeholder = { Text(stringResource(R.string.detail_add_tag_hint)) }
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onAdd) {
                    Text(stringResource(R.string.detail_add))
                }
            }
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (archived) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.detail_archived_hint),
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
    showRaw: Boolean,
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
            if (segment.rawText != segment.correctedText) {
                Text(
                    stringResource(
                        if (segment.isEdited) R.string.detail_segment_edited
                        else R.string.detail_segment_rule_corrected
                    ),
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
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.detail_save))
                    }
                }
                IconButton(onClick = onCancel, enabled = !saving) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            } else if (!showRaw) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.detail_edit_transcript),
                    )
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
                text = if (showRaw) segment.rawText else segment.correctedText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (showRaw) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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
                stringResource(R.string.detail_recording_segments, done, segments.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (failed > 0) {
                    pluralStringResource(
                        R.plurals.detail_preview_failed_count,
                        failed,
                        failed,
                    )
                } else {
                    stringResource(R.string.detail_preview_nonfinal)
                },
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
                if (segment.status == RecordingSegmentStatus.FAILED) {
                    Text(
                        stringResource(R.string.detail_preview_segment_failed),
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
                        contentDescription = stringResource(
                            if (isPlaying) R.string.detail_pause else R.string.detail_play
                        ),
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
    canReprocess: Boolean,
    reprocessing: Boolean,
    reprocessError: String?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit
) {
    val (title, body, accent) = when {
        status == ProcessingStatus.FAILED -> Triple(
            stringResource(R.string.detail_processing_failed_title),
            stringResource(R.string.detail_processing_incomplete),
            MaterialTheme.colorScheme.error
        )
        canReprocess -> Triple(
            stringResource(R.string.detail_processing_empty_title),
            stringResource(R.string.detail_processing_empty_description),
            Color(0xFFE08300)
        )
        status != null && status != ProcessingStatus.COMPLETED -> Triple(
            stringResource(R.string.detail_processing_title),
            stringResource(R.string.detail_processing_stage, labelOf(status)),
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
                    Text(
                        stringResource(R.string.detail_processing_wait),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry, enabled = !reprocessing) {
                    if (reprocessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.detail_processing_starting))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.detail_reprocess))
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
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.detail_dismiss),
                            modifier = Modifier.size(18.dp),
                        )
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
 * 仅重跑 LLM 提取，不动 ASR；前提是 correctedTranscript 非空（否则禁用按钮）。
 */
@Composable
private fun SummaryCard(
    summary: String?,
    draftSummary: String?,
    summaryRevision: Long?,
    currentRevision: Long,
    isStale: Boolean,
    createdAt: Long,
    regenerating: Boolean,
    regenerateError: String?,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDismissError: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val isEmptyOrFallback = summary.isNullOrBlank() ||
        summary.contains(stringResource(R.string.detail_extraction_failed_marker))

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                formatTimestampFull(createdAt, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                if (isStale) {
                    Text(
                        summaryRevision?.let {
                            stringResource(R.string.detail_summary_stale, it, currentRevision)
                        } ?: stringResource(R.string.detail_summary_unbound, currentRevision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                draftSummary?.takeIf { it.isNotBlank() && it != summary }?.let {
                    Text(
                        stringResource(R.string.detail_draft_summary),
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
                        stringResource(R.string.detail_summary_title),
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
                                contentDescription = stringResource(R.string.detail_regenerate_summary),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (isEmptyOrFallback) {
                    Text(
                        summary?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.detail_summary_missing),
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
                            Text(stringResource(R.string.detail_generating))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.detail_regenerate_summary))
                        }
                    }
                    if (!canRegenerate) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.detail_transcript_missing),
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
                            stringResource(R.string.detail_regenerate_failed, err),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        IconButton(onClick = onDismissError) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.detail_dismiss),
                                modifier = Modifier.size(18.dp),
                            )
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

@Composable
private fun recordingSegmentStatusLabel(status: RecordingSegmentStatus): String = when (status) {
    RecordingSegmentStatus.READY -> stringResource(R.string.detail_segment_status_waiting)
    RecordingSegmentStatus.TRANSCRIBING -> stringResource(R.string.detail_segment_status_transcribing)
    RecordingSegmentStatus.TRANSCRIBED -> stringResource(R.string.detail_segment_status_complete)
    RecordingSegmentStatus.FAILED -> stringResource(R.string.status_failed)
}

@Composable
private fun labelOf(s: ProcessingStatus): String = when (s) {
    ProcessingStatus.PENDING -> stringResource(R.string.detail_processing_status_queued)
    ProcessingStatus.RECORDING -> stringResource(R.string.detail_processing_status_recording)
    ProcessingStatus.CONVERTING -> stringResource(R.string.detail_processing_status_converting)
    ProcessingStatus.SPLITTING -> stringResource(R.string.detail_processing_status_splitting)
    ProcessingStatus.TRANSCRIBING -> stringResource(R.string.detail_processing_status_transcribing)
    ProcessingStatus.EXTRACTING -> stringResource(R.string.detail_processing_status_extracting)
    ProcessingStatus.COMPLETED -> stringResource(R.string.detail_segment_status_complete)
    ProcessingStatus.FAILED -> stringResource(R.string.status_failed)
}

private fun String.toTagList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
