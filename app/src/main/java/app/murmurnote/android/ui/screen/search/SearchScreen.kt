package app.murmurnote.android.ui.screen.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.domain.usecase.SearchDateRange
import app.murmurnote.android.domain.usecase.SearchFilters
import app.murmurnote.android.domain.usecase.SearchScope
import app.murmurnote.android.util.formatTimestampFull

@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val q by viewModel.query.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = q,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("搜录音、转写、待办、创意…") },
                singleLine = true
            )
            SearchFilterBar(
                filters = filters,
                onScope = viewModel::setScope,
                onDateRange = viewModel::setDateRange,
                onItemType = viewModel::setItemType
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (result.recordings.isNotEmpty()) {
                    item { Text("录音 (${result.recordings.size})", style = MaterialTheme.typography.titleSmall) }
                    items(result.recordings, key = { "rec-" + it.id }) { rec ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDetail(rec.id) }) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                HighlightedText(
                                    text = rec.title,
                                    query = q,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1
                                )
                                recordingPreview(rec, q, filters)?.let {
                                    HighlightedText(
                                        text = it,
                                        query = q,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                    }
                }
                if (result.items.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("提取项 (${result.items.size})", style = MaterialTheme.typography.titleSmall)
                    }
                    items(result.items, key = { "item-" + it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDetail(item.recordingId) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    formatTimestampFull(item.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                                    HighlightedText(
                                        text = item.content,
                                        query = q,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 4
                                    )
                                    Text(
                                        item.type.name.lowercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                if (q.isNotBlank() && result.recordings.isEmpty() && result.items.isEmpty()) {
                    item {
                        Text(
                            "没找到匹配结果",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterBar(
    filters: SearchFilters,
    onScope: (SearchScope) -> Unit,
    onDateRange: (SearchDateRange) -> Unit,
    onItemType: (ItemType?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SearchScope.ALL to "全部",
                SearchScope.SUMMARY to "总结",
                SearchScope.TRANSCRIPT to "转写",
                SearchScope.ITEMS to "事项"
            ).forEach { (scope, label) ->
                FilterChip(
                    selected = filters.scope == scope,
                    onClick = { onScope(scope) },
                    label = { Text(label) }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                SearchDateRange.ALL to "不限日期",
                SearchDateRange.TODAY to "今天",
                SearchDateRange.SEVEN_DAYS to "7天",
                SearchDateRange.THIRTY_DAYS to "30天"
            ).forEach { (range, label) ->
                FilterChip(
                    selected = filters.dateRange == range,
                    onClick = { onDateRange(range) },
                    label = { Text(label) }
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<ItemType?, String>>(
                null to "全部类型",
                ItemType.TODO to "待办",
                ItemType.IDEA to "想法",
                ItemType.NOTE to "备忘",
                ItemType.DECISION to "决策"
            ).forEach { (type, label) ->
                FilterChip(
                    selected = filters.itemType == type,
                    onClick = { onItemType(type) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE
) {
    val hitColor = MaterialTheme.colorScheme.primary
    val q = query.trim()
    val annotated = buildAnnotatedString {
        if (q.isBlank()) {
            append(text)
            return@buildAnnotatedString
        }
        var start = 0
        while (start < text.length) {
            val index = text.indexOf(q, startIndex = start, ignoreCase = true)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            pushStyle(SpanStyle(color = hitColor, fontWeight = FontWeight.SemiBold))
            append(text.substring(index, index + q.length))
            pop()
            start = index + q.length
        }
    }
    Text(
        annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

private fun recordingPreview(rec: Recording, query: String, filters: SearchFilters): String? {
    val summary = rec.finalSummary ?: rec.summary ?: rec.draftSummary
    val q = query.trim()
    val candidates = when (filters.scope) {
        SearchScope.SUMMARY -> listOf(summary)
        SearchScope.TRANSCRIPT -> listOf(rec.rawTranscript)
        SearchScope.ITEMS -> emptyList()
        SearchScope.ALL -> listOf(summary, rec.rawTranscript)
    }.filterNotNull().filter { it.isNotBlank() }
    val matched = candidates.firstOrNull { q.isNotBlank() && it.contains(q, ignoreCase = true) }
        ?: candidates.firstOrNull()
    return matched?.toSnippet(q)
}

private fun String.toSnippet(query: String, radius: Int = 48): String {
    val q = query.trim()
    if (q.isBlank()) return take(140)
    val index = indexOf(q, ignoreCase = true)
    if (index < 0) return take(140)
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + q.length + radius).coerceAtMost(length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < length) "…" else ""
    return prefix + substring(start, end) + suffix
}
