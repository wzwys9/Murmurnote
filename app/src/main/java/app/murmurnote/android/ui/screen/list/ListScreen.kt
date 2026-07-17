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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
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
                    selectedTag != null -> stringResource(R.string.list_no_tag_matches)
                    hasArchived && !showArchived -> stringResource(R.string.list_all_archived)
                    else -> stringResource(R.string.list_empty)
                }
                val emptyBody = when {
                    selectedTag != null -> stringResource(R.string.list_no_tag_matches_hint)
                    hasArchived && !showArchived -> stringResource(R.string.list_all_archived_hint)
                    else -> stringResource(R.string.list_empty_hint)
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
                label = { Text(stringResource(R.string.list_all_tags)) }
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
                                selectedOverflowTag
                                    ?: stringResource(R.string.list_more_tags, overflowTags.size),
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
            Text(
                stringResource(
                    if (showArchived) R.string.list_hide_archived
                    else R.string.list_show_archived
                )
            )
        }
    }
}

@Composable
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Card(modifier = Modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                formatTimestampFull(rec.createdAt, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                Text(
                    rec.title.stripTrailingTimestamp(rec.createdAt, locale),
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
                        (
                            tags.map { "#$it" } + if (rec.archived) {
                                listOf(stringResource(R.string.list_archived))
                            } else {
                                emptyList()
                            }
                            ).joinToString("  "),
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

@Composable
private fun statusLabel(s: ProcessingStatus): String = when (s) {
    ProcessingStatus.PENDING -> stringResource(R.string.recording_status_pending)
    ProcessingStatus.RECORDING -> stringResource(R.string.recording_status_recording)
    ProcessingStatus.CONVERTING -> stringResource(R.string.recording_status_converting)
    ProcessingStatus.SPLITTING -> stringResource(R.string.recording_status_splitting)
    ProcessingStatus.TRANSCRIBING -> stringResource(R.string.recording_status_transcribing)
    ProcessingStatus.EXTRACTING -> stringResource(R.string.recording_status_extracting)
    ProcessingStatus.COMPLETED -> stringResource(R.string.status_completed)
    ProcessingStatus.FAILED -> stringResource(R.string.status_failed)
}

private fun String.stripTrailingTimestamp(createdAt: Long, locale: java.util.Locale): String {
    val localizedSuffixes = listOf(
        formatTimestampFull(createdAt, locale),
        formatTimestampFull(createdAt, java.util.Locale.ENGLISH),
        formatTimestampFull(createdAt, java.util.Locale.CHINESE),
    ).distinct()
    val withoutLocalizedSuffix = localizedSuffixes.fold(this) { title, suffix ->
        title.removeSuffix(" · $suffix")
    }
    return withoutLocalizedSuffix.replace(
        Regex(
            "\\s*·\\s*\\d{4}\\u5e74\\d{2}\\u6708\\d{2}\\u65e5" +
                "\\s+\\d{2}\\u65f6\\d{2}\\u5206\\d{2}\\u79d2\\s*$"
        ),
        "",
    )
        .ifBlank { this }
}

private fun String.toTagList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
