package app.murmurnote.android.ui.screen.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.data.remote.interceptor.ApiLogCapturePolicy

@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.debug_tab_pipeline),
        stringResource(R.string.debug_tab_api_logs),
        stringResource(R.string.debug_tab_prompt),
        stringResource(R.string.debug_tab_runtime_logs),
        stringResource(R.string.debug_tab_config)
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.debug_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.action_back)
                            )
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error).padding(8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(R.string.debug_banner),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                PrimaryTabRow(selectedTabIndex = tabIndex) {
                    tabs.forEachIndexed { i, name ->
                        Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(name) })
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tabIndex) {
                0 -> PipelineTab()
                1 -> ApiLogTab(viewModel)
                2 -> PromptTab(viewModel)
                3 -> RuntimeLogTab(viewModel)
                4 -> ConfigTab(viewModel)
            }
        }
    }
}

@Composable
private fun PipelineTab() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.debug_pipeline_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.debug_pipeline_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.debug_pipeline_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApiLogTab(vm: DebugViewModel) {
    val logs by vm.apiLogs.collectAsStateWithLifecycle()
    val sharing by vm.shareInProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = vm::clearApiLogs) { Text(stringResource(R.string.debug_clear_logs)) }
            OutlinedButton(onClick = { vm.shareLogBundle(context) }, enabled = !sharing) {
                if (sharing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_share_zip))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(logs, key = { it.id }) { log ->
                var expanded by remember { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(log.apiName, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${log.method} ${log.responseCode} · ${log.durationMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (log.responseCode in 200..399) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            ApiLogCapturePolicy.sanitizeUrl(log.url),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.debug_log_metadata_only))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptTab(vm: DebugViewModel) {
    val sys by vm.systemPrompt.collectAsStateWithLifecycle()
    val usr by vm.userPrompt.collectAsStateWithLifecycle()
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.debug_prompt_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sys,
            onValueChange = vm::setSystemPrompt,
            label = { Text(stringResource(R.string.debug_system_prompt_label)) },
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = usr,
            onValueChange = vm::setUserPrompt,
            label = { Text(stringResource(R.string.debug_user_prompt_label)) },
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::resetPrompts) { Text(stringResource(R.string.debug_restore_defaults)) }
    }
}

@Composable
private fun RuntimeLogTab(vm: DebugViewModel) {
    val lines by vm.runtimeLogLines.collectAsStateWithLifecycle()
    val loading by vm.runtimeLogLoading.collectAsStateWithLifecycle()
    val sharing by vm.shareInProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var levelFilter by remember { mutableStateOf<String?>(null) }      // null = 全部
    val listState = rememberLazyListState()
    val levelOptions = listOf(
        "I" to stringResource(R.string.debug_filter_info),
        "W" to stringResource(R.string.debug_filter_warn),
        "E" to stringResource(R.string.debug_filter_error),
        "D" to stringResource(R.string.debug_filter_debug)
    )

    // 进入 tab 自动拉一次最新 runtime.log；后续靠"刷新"按钮主动拉。
    // 不做实时 tail：避免 ViewModel 持续轮询影响电量，也避免分批加载的复杂状态机。
    LaunchedEffect(Unit) { vm.refreshRuntimeLog() }
    LaunchedEffect(lines.size) {
        // 新内容到达后滚到底部，让用户先看到最近事件
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    val visible = remember(lines, levelFilter) {
        if (levelFilter == null) lines else lines.filter { it.level == levelFilter }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部工具栏：过滤 chips + 操作按钮
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { levelFilter = null },
                    label = { Text(stringResource(R.string.debug_filter_all)) }
                )
                levelOptions.forEach { (lv, lbl) ->
                    FilterChip(
                        selected = levelFilter == lv,
                        onClick = { levelFilter = lv },
                        label = { Text(lbl) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshRuntimeLog() }, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(stringResource(R.string.action_refresh))
                }
                OutlinedButton(onClick = vm::clearRuntimeLog) {
                    Text(stringResource(R.string.debug_clear))
                }
                OutlinedButton(onClick = { vm.shareLogBundle(context) }, enabled = !sharing) {
                    if (sharing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_share_zip))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.debug_log_line_count, visible.size, lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (lines.isEmpty()) stringResource(R.string.debug_no_runtime_logs)
                    else stringResource(R.string.debug_no_matching_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 横滑：崩溃栈帧单行很长，用 horizontalScroll 而非 wrap，避免误读折行
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(visible) { line ->
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text(
                            text = line.raw,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colorForLevel(line.level)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun colorForLevel(level: String): Color = when (level) {
    "E" -> MaterialTheme.colorScheme.error
    "W" -> Color(0xFFE08300)
    "D" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun ConfigTab(vm: DebugViewModel) {
    val forceFail by vm.forceFail.collectAsStateWithLifecycle()
    val delay by vm.simulateDelayMs.collectAsStateWithLifecycle()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.debug_force_network_failure),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Switch(checked = forceFail, onCheckedChange = { vm.toggleForceFail() })
        }
        Text(stringResource(R.string.debug_simulated_delay), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L, 500L, 2000L, 10000L).forEach { v ->
                FilterChip(
                    selected = delay == v,
                    onClick = { vm.setSimulateDelay(v) },
                    label = { Text("${v}ms") }
                )
            }
        }
    }
}
