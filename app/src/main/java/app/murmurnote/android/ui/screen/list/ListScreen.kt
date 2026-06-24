package app.murmurnote.android.ui.screen.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.util.formatDurationMs
import app.murmurnote.android.util.formatTimestampFull

private const val MAX_VISIBLE_FILTER_TAGS = 4

@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (String) -> Unit,
    viewModel: ListViewModel = hiltViewModel()
) {
    val list by viewModel.recordings.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val hasArchived by viewModel.hasArchived.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val showArchived by viewModel.showArchived.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        if (allTags.isNotEmpty() || hasArchived || showArchived || selectedTag != null) {
            RecordingFilterBar(
                allTags = allTags,
                selectedTag = selectedTag,
                showArchived = showArchived,
                onSelectTag = viewModel::selectTag,
                onToggleArchived = viewModel::toggleShowArchived
            )
        }
        if (list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                val emptyTitle = when {
                    selectedTag != null -> "没有匹配标签的录音"
                    hasArchived && !showArchived -> "录音都已归档"
                    else -> "还没有录音"
                }
                val emptyBody = when {
                    selectedTag != null -> "换一个标签或显示归档后再试"
                    hasArchived && !showArchived -> "点上方显示归档即可查看"
                    else -> "回到首页按下大圆按钮，开始你的第一段语音备忘"
                }
                Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    emptyBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { rec ->
                    RecordingRow(rec, onClick = { onOpenDetail(rec.id) })
                }
            }
        }
    }
}

@Composable
private fun RecordingFilterBar(
    allTags: List<String>,
    selectedTag: String?,
    showArchived: Boolean,
    onSelectTag: (String?) -> Unit,
    onToggleArchived: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val visibleTags = allTags.take(MAX_VISIBLE_FILTER_TAGS)
        val overflowTags = allTags.drop(MAX_VISIBLE_FILTER_TAGS)
        val selectedOverflowTag = selectedTag?.takeIf { it in overflowTags }
        var tagMenuExpanded by remember(allTags) { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedTag == null,
                onClick = { onSelectTag(null) },
                label = { Text("全部标签") }
            )
            visibleTags.forEach { tag ->
                FilterChip(
                    selected = selectedTag == tag,
                    onClick = { onSelectTag(tag) },
                    label = { Text(tag) }
                )
            }
            if (overflowTags.isNotEmpty()) {
                Box {
                    FilterChip(
                        selected = selectedOverflowTag != null,
                        onClick = { tagMenuExpanded = true },
                        label = {
                            Text(
                                selectedOverflowTag ?: "更多 ${overflowTags.size}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = tagMenuExpanded,
                        onDismissRequest = { tagMenuExpanded = false }
                    ) {
                        overflowTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tag) },
                                onClick = {
                                    tagMenuExpanded = false
                                    onSelectTag(tag)
                                }
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onToggleArchived) {
            Text(if (showArchived) "隐藏归档" else "显示归档")
        }
    }
}

@Composable
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                formatTimestampFull(rec.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Text(
                    rec.title.stripTrailingTimestamp(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                val meta = buildString {
                    append(formatDurationMs(rec.durationMs))
                    if (rec.processingStatus != ProcessingStatus.COMPLETED) {
                        append(" · ").append(statusLabel(rec.processingStatus))
                    }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val tags = rec.tags.toTagList()
                if (tags.isNotEmpty() || rec.archived) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (tags.map { "#$it" } + if (rec.archived) listOf("已归档") else emptyList()).joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                (rec.finalSummary ?: rec.summary)?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
        }
    }
}

private fun statusLabel(s: ProcessingStatus): String = when (s) {
    ProcessingStatus.PENDING -> "待处理"
    ProcessingStatus.RECORDING -> "录音中"
    ProcessingStatus.CONVERTING -> "转码中"
    ProcessingStatus.SPLITTING -> "切片中"
    ProcessingStatus.TRANSCRIBING -> "转写中"
    ProcessingStatus.EXTRACTING -> "AI 提取中"
    ProcessingStatus.COMPLETED -> "完成"
    ProcessingStatus.FAILED -> "失败"
}

private fun String.stripTrailingTimestamp(): String =
    replace(Regex("\\s*·\\s*\\d{4}年\\d{2}月\\d{2}日\\s+\\d{2}时\\d{2}分\\d{2}秒\\s*$"), "")
        .ifBlank { this }

private fun String.toTagList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
