package app.murmurnote.android.ui.screen.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.BuildConfig

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateToDebug: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshOllamaModels() }

    var versionClickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item { SettingSectionHeader("账号与API") }
        item {
            ApiKeySettingItem(
                title = "智谱 GLM API Key",
                description = "用于语音转文字 (GLM-ASR-2512)",
                placeholder = "请输入您的智谱 API Key",
                value = state.glmApiKey,
                isConfigured = state.glmApiKey.isNotBlank(),
                onValueChange = viewModel::updateGlmApiKey,
                onTest = viewModel::testGlmConnection,
                testStatus = state.glmTestStatus,
                helpUrl = "https://bigmodel.cn/usercenter/apikeys"
            )
        }
        item {
            ApiKeySettingItem(
                title = "Ollama API Key",
                description = "用于AI文本提取",
                placeholder = "请输入您的 Ollama API Key",
                value = state.ollamaApiKey,
                isConfigured = state.ollamaApiKey.isNotBlank(),
                onValueChange = viewModel::updateOllamaApiKey,
                onTest = viewModel::testOllamaConnection,
                testStatus = state.ollamaTestStatus,
                helpUrl = "https://ollama.com/settings/keys"
            )
        }

        item {
            ExpandableSection("高级设置") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.glmBaseUrl,
                        onValueChange = viewModel::updateGlmBaseUrl,
                        label = { Text("GLM Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.ollamaBaseUrl,
                        onValueChange = viewModel::updateOllamaBaseUrl,
                        label = { Text("Ollama Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "默认值适合大多数用户。仅当您使用代理或自部署服务时修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SettingSectionHeader("AI模型") }
        item {
            OllamaModelSelector(
                currentModel = state.ollamaModel,
                availableModels = state.availableOllamaModels,
                isLoading = state.isLoadingModels,
                onRefresh = viewModel::refreshOllamaModels,
                onModelSelected = viewModel::updateOllamaModel,
                error = state.modelLoadError
            )
        }
        item {
            ReasoningEffortSelector(
                current = state.reasoningEffort,
                onSelected = viewModel::updateReasoningEffort
            )
        }

        item { SettingSectionHeader("关于") }
        item {
            ListItem(
                headlineContent = { Text("版本") },
                trailingContent = { Text(BuildConfig.VERSION_NAME) },
                modifier = Modifier.clickable {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime > 1500) versionClickCount = 1
                    else {
                        versionClickCount++
                        if (versionClickCount >= 7) {
                            onNavigateToDebug(); versionClickCount = 0
                        }
                    }
                    lastClickTime = now
                }
            )
        }

        item { SettingSectionHeader("日志") }
        item {
            val ctx = LocalContext.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("运行日志", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "打包成 zip。包含：runtime.log（录音/Pipeline/API/设置/崩溃事件）、" +
                            "runtime.log.old（轮转备份）、api_logs.txt（最近 100 条 HTTP 含完整请求/响应 body）、meta.txt（设备与版本信息）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.exportLog() },
                            enabled = !state.exportingLog
                        ) {
                            if (state.exportingLog) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("处理中…")
                            } else Text("导出到 Downloads")
                        }
                        OutlinedButton(
                            onClick = { viewModel.shareLog(ctx) },
                            enabled = !state.exportingLog
                        ) {
                            Text("分享")
                        }
                    }
                    state.exportLogResult?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("✓")) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

@Composable
fun ApiKeySettingItem(
    title: String,
    description: String,
    placeholder: String,
    value: String,
    isConfigured: Boolean,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit,
    testStatus: TestStatus,
    helpUrl: String
) {
    var expanded by remember { mutableStateOf(false) }
    // 默认明文显示：长按全选 / 复制 / 粘贴等系统菜单在密码字段下会被屏蔽，
    // 而 API Key 是个人自用工具的核心配置，编辑友好优先于"看一眼隐私"。
    // 用户可点眼睛图标切换为遮罩。
    var showKey by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(isConfigured)
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text(placeholder) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        // KeyboardType.Ascii：禁用 IME 自动建议/纠错/自动填充，
                        // 否则某些输入法在删空后会"建议"恢复刚刚的值（与 DataStore 兜底逻辑叠加更明显）。
                        // 同时这是普通文本框，长按菜单（剪切/复制/全选/粘贴）正常出现。
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        singleLine = true
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onTest,
                            enabled = value.isNotBlank() && testStatus !is TestStatus.Testing
                        ) {
                            if (testStatus is TestStatus.Testing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else Text("测试连接")
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, helpUrl.toUri()))
                        }) { Text("如何获取?") }
                    }
                    when (val ts = testStatus) {
                        is TestStatus.Success -> Text("✓ 连接成功", color = Color(0xFF4CAF50))
                        is TestStatus.Failed -> Text("✗ ${ts.message}", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(configured: Boolean) {
    val text = if (configured) "已配置" else "未配置"
    val color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .height(24.dp)
            .clickable {}
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun OllamaModelSelector(
    currentModel: String,
    availableModels: List<String>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onModelSelected: (String) -> Unit,
    error: String?
) {
    var expanded by remember { mutableStateOf(false) }
    val recommended = remember {
        listOf(
            "deepseek-v4-flash" to "推荐：快速响应（默认）",
            "deepseek-v4-pro" to "最强推理（慢）",
            "glm-4.7" to "智谱 GLM-4.7",
            "kimi-k2.6" to "Moonshot Kimi",
            "qwen3-coder:480b" to "代码场景"
        )
    }
    val display = if (availableModels.isNotEmpty()) availableModels else recommended.map { it.first }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text("Ollama 模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "选择用于AI提取的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("当前模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    display.forEach { model ->
                        val desc = recommended.firstOrNull { it.first == model }?.second
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model, fontWeight = FontWeight.Medium)
                                    if (desc != null) Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = { onModelSelected(model); expanded = false },
                            trailingIcon = if (model == currentModel) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null
                        )
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ $error\n显示的是推荐模型列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun ReasoningEffortSelector(current: String, onSelected: (String) -> Unit) {
    val options = listOf(
        Triple("none", "关闭", "最快，无思考"),
        Triple("low", "低（推荐）", "平衡速度与质量"),
        Triple("medium", "中", "更深入分析"),
        Triple("high", "高", "最慢，最深入")
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("思考强度", style = MaterialTheme.typography.titleMedium)
            Text(
                "AI在提取前的思考深度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            options.forEach { (value, label, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected(value) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = current == value, onClick = { onSelected(value) })
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(label)
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) { content() }
            }
        }
    }
}
