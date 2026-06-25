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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
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
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.AsrModelUrls
import app.murmurnote.android.data.asr.LocalAsrModelSpec
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.data.remote.llm.LlmProvider

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateToDebug: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val llmProvider = LlmProvider.parse(state.llmProvider)
    val isLocalAsr = AsrEngineType.parse(state.asrEngineType).isLocal()

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
                        value = state.llmBaseUrl,
                        onValueChange = viewModel::updateLlmBaseUrl,
                        label = { Text("${llmProvider.displayName} Base URL") },
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

        item { SettingSectionHeader("语音识别引擎") }
        item {
            RealtimePerformanceSection(
                mode = state.realtimePerformanceMode,
                lowBatteryProtection = state.lowBatteryProtection,
                onModeSelected = viewModel::setRealtimePerformanceMode,
                onLowBatteryProtectionChanged = viewModel::setLowBatteryProtection
            )
        }
        item {
            AsrEngineSelectorCard(
                engineType = state.asrEngineType,
                onEngineSelected = viewModel::setAsrEngineType
            )
        }
        if (isLocalAsr) item {
            LocalAsrModelCard(
                nativeLibReady = state.asrNativeLibReady,
                localModels = state.asrLocalModels,
                selectedModelId = state.asrLocalModelId,
                onModelSelected = viewModel::setAsrLocalModel
            )
        }
        if (isLocalAsr) item {
            val ctx = LocalContext.current
            LocalModelStatusBlock(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                status = state.asrModelStatus,
                model = AsrModelUrls.modelById(state.asrLocalModelId),
                updateCheck = state.asrModelUpdateCheck,
                updateChecking = state.asrModelUpdateChecking,
                bundledAssetsAvailable = state.asrBundledAssetsAvailable,
                mirrorIndex = state.asrMirrorIndex,
                mirrorOptions = state.asrMirrorOptions,
                localConcurrency = state.asrLocalConcurrency,
                onMirrorSelected = viewModel::setAsrMirrorIndex,
                onConcurrencyChanged = viewModel::setAsrLocalConcurrency,
                onInstallBundledModel = viewModel::installBundledAsrModel,
                onRequestDownload = viewModel::requestAsrDownloadConfirm,
                onRequestInstallHashMismatch = viewModel::requestInstallHashMismatchModel,
                onCheckModelUpdate = viewModel::checkAsrModelUpdate,
                onCancelDownload = { viewModel.cancelAsrDownload(ctx) },
                onDeleteModel = viewModel::deleteAsrModel
            )
        }
        if (state.showAsrDownloadConfirm) {
            item {
                AsrDownloadConfirmDialog(
                    model = AsrModelUrls.modelById(state.asrLocalModelId),
                    onDismiss = viewModel::dismissAsrDownloadConfirm,
                    onConfirm = { viewModel.startAsrDownload(it) }
                )
            }
        }
        if (state.showAsrHashMismatchConfirm && state.asrModelStatus is AsrModelManager.ModelStatus.HashMismatch) {
            item {
                AsrHashMismatchConfirmDialog(
                    model = AsrModelUrls.modelById(state.asrLocalModelId),
                    status = state.asrModelStatus as AsrModelManager.ModelStatus.HashMismatch,
                    onDismiss = viewModel::dismissInstallHashMismatchModel,
                    onConfirm = { viewModel.installHashMismatchModel(it) }
                )
            }
        }

        item { SettingSectionHeader("AI模型") }
        item {
            AiExtractionSwitch(
                enabled = state.aiExtractionEnabled,
                onEnabledChange = viewModel::setAiExtractionEnabled
            )
        }
        if (state.aiExtractionEnabled) item {
            ApiKeySettingItem(
                title = "${llmProvider.displayName} API Key",
                description = "用于 AI 文本提取与总结",
                placeholder = "请输入您的 ${llmProvider.displayName} API Key",
                value = state.llmApiKey,
                isConfigured = state.llmApiKey.isNotBlank(),
                onValueChange = viewModel::updateLlmApiKey,
                onTest = viewModel::testLlmConnection,
                testStatus = state.llmTestStatus,
                helpUrl = llmProvider.apiKeyHelpUrl
            )
        }
        if (state.aiExtractionEnabled) item {
            LlmProviderSelector(
                currentProvider = state.llmProvider,
                onProviderSelected = viewModel::updateLlmProvider
            )
        }
        if (state.aiExtractionEnabled) item {
            LlmModelSelector(
                provider = llmProvider,
                currentModel = state.llmModel,
                availableModels = state.availableLlmModels,
                isLoading = state.isLoadingModels,
                onRefresh = viewModel::refreshLlmModels,
                onModelSelected = viewModel::updateLlmModel,
                error = state.modelLoadError
            )
        }
        if (state.aiExtractionEnabled) item {
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
        item {
            AppUpdateCheckCard(
                status = state.appUpdateStatus,
                onCheck = { viewModel.checkAppUpdate(BuildConfig.VERSION_NAME) }
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
private fun AppUpdateCheckCard(
    status: AppUpdateStatus,
    onCheck: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("检查更新", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "从 GitHub Releases 获取最新版本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onCheck,
                    enabled = status !is AppUpdateStatus.Checking
                ) {
                    if (status is AppUpdateStatus.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("检查中…")
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("检查")
                    }
                }
            }

            when (status) {
                AppUpdateStatus.Idle -> Unit
                AppUpdateStatus.Checking -> Text(
                    "正在检查最新版本…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is AppUpdateStatus.UpToDate -> Text(
                    "已是最新版本：${status.latestVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
                is AppUpdateStatus.UpdateAvailable -> {
                    Text(
                        "发现新版本：${status.latestVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, status.releaseUrl.toUri()))
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("打开下载页")
                    }
                }
                is AppUpdateStatus.Failed -> Text(
                    "检查失败：${status.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun RealtimePerformanceSection(
    mode: String,
    lowBatteryProtection: Boolean,
    onModeSelected: (String) -> Unit,
    onLowBatteryProtectionChanged: (Boolean) -> Unit
) {
    val options = remember {
        listOf(
            "OFF" to ("关闭" to "录音中不做实时转写和滚动总结，停止后完整处理"),
            "POWER_SAVE" to ("省电" to "保留实时转写，降低滚动总结频率"),
            "BALANCED" to ("平衡" to "默认实时转写和滚动总结频率"),
            "FAST" to ("快速" to "更频繁更新滚动总结，耗电更高")
        )
    }
    val selected = options.firstOrNull { it.first == mode } ?: options.first { it.first == "BALANCED" }
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("实时处理性能", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selected.second.first,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("处理模式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(label.first, fontWeight = FontWeight.Medium)
                                    Text(
                                        label.second,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onModeSelected(value)
                            },
                            trailingIcon = if (mode == value) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null
                        )
                    }
                }
            }
            Text(
                selected.second.second,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("低电量保护", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "电量低于 20% 时暂停滚动总结，只保留录音和必要转写",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = lowBatteryProtection,
                    onCheckedChange = onLowBatteryProtectionChanged
                )
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
fun AiExtractionSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("AI 总结和事项提取", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "录音完成后生成总结、待办、想法和决策。"
                    else "关闭后只做语音转文字，不调用 AI 文本模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
fun LlmProviderSelector(
    currentProvider: String,
    onProviderSelected: (String) -> Unit
) {
    val current = LlmProvider.parse(currentProvider)
    val providers = LlmProvider.entries
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("官方模式", style = MaterialTheme.typography.titleMedium)
            Text(
                "切换后从对应官方接口拉取可用模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = current.providerLabel(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("当前官方模式") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(provider.providerLabel(), fontWeight = FontWeight.Medium)
                                    Text(
                                        provider.defaultBaseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onProviderSelected(provider.name)
                            },
                            trailingIcon = if (provider == current) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

private fun LlmProvider.providerLabel(): String =
    if (this == LlmProvider.OLLAMA) "Ollama Cloud" else displayName

@Composable
fun LlmModelSelector(
    provider: LlmProvider,
    currentModel: String,
    availableModels: List<String>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onModelSelected: (String) -> Unit,
    error: String?
) {
    var expanded by remember { mutableStateOf(false) }
    val display = remember(availableModels, currentModel) {
        if (currentModel.isNotBlank() && currentModel !in availableModels) {
            listOf(currentModel) + availableModels
        } else {
            availableModels
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text("${provider.displayName} 模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "从 ${provider.displayName} 官方模型接口获取",
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
                    label = { Text(if (currentModel.isBlank()) "请刷新并选择模型" else "当前模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (display.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("请先刷新模型列表") },
                            onClick = { expanded = false },
                            enabled = false
                        )
                    } else {
                        display.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model, fontWeight = FontWeight.Medium) },
                                onClick = { onModelSelected(model); expanded = false },
                                trailingIcon = if (model == currentModel) {
                                    { Icon(Icons.Filled.Check, null) }
                                } else null
                            )
                        }
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "无法从官方接口获取模型列表：$error",
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
        Triple("none", "关闭", "不思考，最快响应"),
        Triple("high", "高（推荐）", "DeepSeek 默认思考强度"),
        Triple("max", "最大", "最深度思考，复杂任务更准")
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("思考深度", style = MaterialTheme.typography.titleMedium)
            Text(
                "支持的供应商会按官方 thinking / reasoning 参数发送",
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
fun AsrEngineSelectorCard(
    engineType: String,
    onEngineSelected: (String) -> Unit
) {
    val isLocal = AsrEngineType.parse(engineType).isLocal()

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别引擎", style = MaterialTheme.typography.titleMedium)

            EngineRadioRow(
                title = "云端（智谱 GLM-ASR）",
                subtitle = "准确率高、依赖网络与 API Key、按量计费",
                selected = engineType == AsrEngineType.CLOUD_GLM.name,
                onSelected = { onEngineSelected(AsrEngineType.CLOUD_GLM.name) }
            )
            EngineRadioRow(
                title = "本地模型",
                subtitle = "完全离线，可在下方选择 SenseVoiceSmall 或 Qwen3-ASR",
                selected = isLocal,
                onSelected = { onEngineSelected(AsrEngineType.LOCAL_SENSE_VOICE.name) }
            )
        }
    }
}

@Composable
fun LocalAsrModelCard(
    nativeLibReady: Boolean,
    localModels: List<LocalAsrModelSpec>,
    selectedModelId: String,
    onModelSelected: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeLibStatusRow(nativeLibReady)
            LocalModelPicker(
                models = localModels,
                selectedModelId = selectedModelId,
                onSelected = onModelSelected
            )
        }
    }
}

@Composable
private fun NativeLibStatusRow(ready: Boolean) {
    val color = if (ready) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    val text = if (ready) "✓ sherpa-onnx 原生库已集成"
        else "✗ sherpa-onnx 原生库未集成（开发者需集成 Kotlin 绑定和 JNI 库后重新构建；模型文件即使下完也无法运行）"
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun EngineRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocalModelPicker(
    models: List<LocalAsrModelSpec>,
    selectedModelId: String,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "本地模型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        models.forEach { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(model.id) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedModelId == model.id, onClick = { onSelected(model.id) })
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${model.description} · 下载 ${model.sizeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelStatusBlock(
    modifier: Modifier = Modifier,
    status: AsrModelManager.ModelStatus,
    model: LocalAsrModelSpec,
    updateCheck: AsrModelManager.ModelUpdateCheck?,
    updateChecking: Boolean,
    bundledAssetsAvailable: Boolean,
    mirrorIndex: Int,
    mirrorOptions: List<String>,
    localConcurrency: Int,
    onMirrorSelected: (Int) -> Unit,
    onConcurrencyChanged: (Int) -> Unit,
    onInstallBundledModel: () -> Unit,
    onRequestDownload: () -> Unit,
    onRequestInstallHashMismatch: () -> Unit,
    onCheckModelUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                AsrModelManager.ModelStatus.NotDownloaded -> {
                    Text("模型未下载", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "首次启用 ${model.displayName} 前需要安装模型文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (bundledAssetsAvailable) {
                        Text(
                            "当前 APK 已内置该模型，安装会复制约 ${model.sizeLabel} 到本机存储；复制期间可能占用较多 I/O，建议空闲时操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onInstallBundledModel) { Text("安装内置模型") }
                            OutlinedButton(onClick = onRequestDownload) { Text("从网络下载") }
                        }
                    } else {
                        MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                        Button(onClick = onRequestDownload) { Text("下载模型（约 ${model.sizeLabel}）") }
                    }
                }
                is AsrModelManager.ModelStatus.Downloading -> {
                    Text("下载中：${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "速度：${formatSpeed(status.bytesPerSec)} · 剩余 ${formatEta(status.etaSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (status.bytesPerSec in 1..(50 * 1024)) {
                        Text(
                            "下载速度较慢，可在下方切换镜像源。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                    OutlinedButton(onClick = onCancelDownload) { Text("取消下载") }
                }
                is AsrModelManager.ModelStatus.Extracting -> {
                    Text("解压中：${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is AsrModelManager.ModelStatus.Ready -> {
                    Text("✓ 模型已就绪", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CAF50))
                    Text(
                        "占用空间：${formatSize(status.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.supportsFastConcurrency) {
                        ConcurrencySelector(localConcurrency, onConcurrencyChanged)
                    } else {
                        Text(
                            "Qwen3-ASR 内存占用较高，本地识别固定单路运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ModelUpdateCheckResult(updateCheck)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onCheckModelUpdate,
                            enabled = !updateChecking
                        ) {
                            if (updateChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(if (updateChecking) "检测中…" else "检测更新")
                        }
                        OutlinedButton(onClick = onDeleteModel) { Text("删除模型") }
                    }
                    if (updateCheck is AsrModelManager.ModelUpdateCheck.UpdateAvailable) {
                        Button(onClick = onRequestDownload) { Text("下载更新") }
                    }
                }
                is AsrModelManager.ModelStatus.HashMismatch -> {
                    Text("✗ 模型校验不匹配", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        "下载文件的 SHA256 与内置校验值不一致。建议重新下载；确认来源可信时可以继续安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestDownload) { Text("重新下载") }
                        OutlinedButton(onClick = onRequestInstallHashMismatch) { Text("继续安装") }
                    }
                }
                is AsrModelManager.ModelStatus.Corrupted -> {
                    Text("✗ 模型已损坏", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestDownload) { Text("重新下载") }
                        OutlinedButton(onClick = onDeleteModel) { Text("删除") }
                    }
                }
                is AsrModelManager.ModelStatus.Failed -> {
                    Text("✗ 下载失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                    Button(onClick = onRequestDownload) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun ModelUpdateCheckResult(result: AsrModelManager.ModelUpdateCheck?) {
    if (result == null) return
    val (text, color) = when (result) {
        AsrModelManager.ModelUpdateCheck.NotInstalled ->
            "模型尚未安装，下载后再检测更新。" to MaterialTheme.colorScheme.onSurfaceVariant
        is AsrModelManager.ModelUpdateCheck.UpToDate ->
            result.message to Color(0xFF4CAF50)
        is AsrModelManager.ModelUpdateCheck.UpdateAvailable ->
            result.message to MaterialTheme.colorScheme.primary
        is AsrModelManager.ModelUpdateCheck.UnableToCheck ->
            result.message to MaterialTheme.colorScheme.error
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun MirrorPicker(
    current: Int,
    options: List<String>,
    onSelected: (Int) -> Unit
) {
    Column {
        Text(
            "下载源",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        options.forEachIndexed { i, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(i) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = current == i, onClick = { onSelected(i) })
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConcurrencySelector(
    current: Int,
    onChanged: (Int) -> Unit
) {
    Column {
        Text(
            "并行识别速度（约 ${current}x，最多 3x）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { n ->
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onChanged(n) },
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (current == n) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("${n}x", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun AsrDownloadConfirmDialog(
    model: LocalAsrModelSpec,
    onDismiss: () -> Unit,
    onConfirm: (android.content.Context) -> Unit
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载本地 ASR 模型") },
        text = {
            Text(
                "${model.displayName} 压缩包约 ${model.sizeLabel}。国内网络下载可能需要较长时间，建议在 WiFi 下进行。\n\n" +
                    "下载会在通知栏显示进度，可随时取消并继续（断点续传）。"
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(ctx) }) { Text("开始下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun AsrHashMismatchConfirmDialog(
    model: LocalAsrModelSpec,
    status: AsrModelManager.ModelStatus.HashMismatch,
    onDismiss: () -> Unit,
    onConfirm: (android.content.Context) -> Unit
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型校验不匹配") },
        text = {
            Text(
                "${model.displayName} 的下载文件与内置 SHA256 不一致。\n\n" +
                    "期望：${status.expected.take(12)}…\n" +
                    "实际：${status.actual.take(12)}…\n\n" +
                    "继续安装可能使用被篡改或损坏的模型。"
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(ctx) }) { Text("仍然安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> "—"
    bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / (1024.0 * 1024.0))
    else -> "${bps / 1024} KB/s"
}

private fun formatEta(sec: Long): String = when {
    sec <= 0 -> "—"
    sec >= 3600 -> "%dh %02dm".format(sec / 3600, (sec % 3600) / 60)
    sec >= 60 -> "%dm %02ds".format(sec / 60, sec % 60)
    else -> "${sec}s"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
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
