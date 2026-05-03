package app.murmurnote.android.ui.screen.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.util.formatDurationMs
import app.murmurnote.android.util.formatTimestampFull

@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    onOpenDetail: (String) -> Unit,
    viewModel: ListViewModel = hiltViewModel()
) {
    val list by viewModel.recordings.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text("还没有录音", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "回到首页按下大圆按钮，开始你的第一段语音备忘",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(list, key = { it.id }) { rec ->
            RecordingRow(rec, onClick = { onOpenDetail(rec.id) })
        }
    }
}

@Composable
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题占左侧主区，右上角小字时间戳与 TodoScreen / 详情页保持一致格式。
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    rec.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimestampFull(rec.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            rec.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
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
