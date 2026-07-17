package app.murmurnote.android.ui.screen.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.ui.component.FirstLineAlignedCheckboxRow
import app.murmurnote.android.util.formatTimestampFull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodoScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (String) -> Unit,
    viewModel: TodoViewModel = hiltViewModel()
) {
    val list by viewModel.todos.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.todo_empty), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.todo_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(list, key = { it.id }) { item ->
            TodoRow(item, onToggle = { viewModel.toggle(item.id, it) }, onClick = { onOpenDetail(item.recordingId) })
        }
    }
}

@Composable
private fun TodoRow(item: ExtractedItem, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val deadlineText = item.deadline?.let {
        formatTodoDeadline(
            deadlineMs = it,
            zoneId = ZoneId.systemDefault(),
            locale = locale,
            datePattern = stringResource(R.string.todo_deadline_pattern),
            template = stringResource(R.string.todo_deadline),
        )
    }
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                formatTimestampFull(item.createdAt, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            FirstLineAlignedCheckboxRow(
                checked = item.isCompleted,
                onCheckedChange = onToggle,
                text = item.content,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                horizontalSpacing = 8.dp,
            ) {
                deadlineText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal fun formatTodoDeadline(
    deadlineMs: Long,
    zoneId: ZoneId,
    locale: Locale,
    datePattern: String,
    template: String,
): String {
    val formatter = DateTimeFormatter.ofPattern(datePattern, locale).withZone(zoneId)
    val date = formatter.format(Instant.ofEpochMilli(deadlineMs))
    return String.format(locale, template, date)
}
