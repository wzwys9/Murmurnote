package app.murmurnote.android.ui.screen.search

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.util.formatTimestampFull

@Composable
fun SearchScreen(
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val q by viewModel.query.collectAsStateWithLifecycle()
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

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (result.recordings.isNotEmpty()) {
                    item { Text("录音 (${result.recordings.size})", style = MaterialTheme.typography.titleSmall) }
                    items(result.recordings, key = { "rec-" + it.id }) { rec ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDetail(rec.id) }) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(rec.title, style = MaterialTheme.typography.titleSmall)
                                rec.summary?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text(
                                        item.content,
                                        style = MaterialTheme.typography.bodyMedium
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
