package app.murmurnote.android.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    val asrSettingsPanel = resolveAsrSettingsPanel(state.asrEngineType)

    var versionClickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }
    var asrSettingsDetail by rememberSaveable {
        mutableStateOf<AsrSettingsPanel?>(null)
    }
    var showLaboratory by rememberSaveable { mutableStateOf(false) }
    var showSafeLexicon by rememberSaveable { mutableStateOf(false) }

    BackHandler(
        enabled = showSafeLexicon || showLaboratory || asrSettingsDetail != null,
    ) {
        when {
            showSafeLexicon -> showSafeLexicon = false
            showLaboratory -> showLaboratory = false
            else -> asrSettingsDetail = null
        }
    }
    if (showSafeLexicon) {
        SafeLexiconScreen(
            modifier = modifier,
            masterEnabled = state.safeLexiconEnabled && state.llmApiKey.isNotBlank(),
            llmApiConfigured = state.llmApiKey.isNotBlank(),
            onMasterEnabledChange = viewModel::setSafeLexiconEnabled,
            onBack = { showSafeLexicon = false },
        )
        return
    }
    if (showLaboratory) {
        LaboratoryScreen(
            modifier = modifier,
            safeLexiconEnabled = state.safeLexiconEnabled && state.llmApiKey.isNotBlank(),
            llmApiConfigured = state.llmApiKey.isNotBlank(),
            onSafeLexiconEnabledChange = viewModel::setSafeLexiconEnabled,
            onManageSafeLexicon = { showSafeLexicon = true },
            onBack = { showLaboratory = false },
        )
        return
    }
    asrSettingsDetail?.let { detail ->
        AsrSettingsDetailScreen(
            modifier = modifier,
            panel = detail,
            state = state,
            viewModel = viewModel,
            onBack = { asrSettingsDetail = null },
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item { SettingSectionHeader("语音识别") }
        item {
            AsrSettingsDirectoryCard(
                panel = AsrSettingsPanel.CLOUD,
                selected = asrSettingsPanel == AsrSettingsPanel.CLOUD,
                statusText = asrSettingsStatusText(
                    panel = AsrSettingsPanel.CLOUD,
                    selected = asrSettingsPanel == AsrSettingsPanel.CLOUD,
                    cloudApiConfigured = state.glmApiKey.isNotBlank(),
                    localModelDisplayName = AsrModelUrls.modelById(
                        state.asrLocalModelId,
                    ).displayName,
                ),
                onSelect = {
                    viewModel.setAsrEngineType(AsrEngineType.CLOUD_GLM.name)
                },
                onConfigure = {
                    asrSettingsDetail = AsrSettingsPanel.CLOUD
                },
            )
        }
        item {
            AsrSettingsDirectoryCard(
                panel = AsrSettingsPanel.LOCAL,
                selected = asrSettingsPanel == AsrSettingsPanel.LOCAL,
                statusText = asrSettingsStatusText(
                    panel = AsrSettingsPanel.LOCAL,
                    selected = asrSettingsPanel == AsrSettingsPanel.LOCAL,
                    cloudApiConfigured = state.glmApiKey.isNotBlank(),
                    localModelDisplayName = AsrModelUrls.modelById(
                        state.asrLocalModelId,
                    ).displayName,
                ),
                onSelect = {
                    viewModel.setAsrEngineType(AsrEngineType.LOCAL_SENSE_VOICE.name)
                },
                onConfigure = {
                    asrSettingsDetail = AsrSettingsPanel.LOCAL
                },
            )
        }
        item {
            SettingGroupHeader(
                title = "录音时处理",
                description = "两种识别引擎共用的实时处理与省电选项。"
            )
        }
        item {
            RealtimePerformanceSection(
                mode = state.realtimePerformanceMode,
                lowBatteryProtection = state.lowBatteryProtection,
                onModeSelected = viewModel::setRealtimePerformanceMode,
                onLowBatteryProtectionChanged = viewModel::setLowBatteryProtection
            )
        }

        item { SettingSectionHeader("AI 文本整理") }
        item {
            AiExtractionSwitch(
                enabled = state.aiExtractionEnabled,
                onEnabledChange = viewModel::setAiExtractionEnabled
            )
        }
        if (state.aiExtractionEnabled) {
            item {
                LlmProviderSelector(
                    currentProvider = state.llmProvider,
                    onProviderSelected = viewModel::updateLlmProvider
                )
            }
            item {
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
            item {
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
            item {
                ReasoningEffortSelector(
                    current = state.reasoningEffort,
                    onSelected = viewModel::updateReasoningEffort
                )
            }
            item {
                ExpandableSection("AI 服务高级设置") {
                    ServiceBaseUrlField(
                        value = state.llmBaseUrl,
                        onValueChange = viewModel::updateLlmBaseUrl,
                        label = "${llmProvider.displayName} Base URL"
                    )
                }
            }
        }

        item { SettingSectionHeader("更多") }
        item {
            LaboratoryDirectoryCard(
                onOpen = { showLaboratory = true },
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
                            "runtime.log.old（轮转备份）、api_logs.txt（最近 100 条 HTTP 方法、主机、状态码和耗时等元数据，不含正文或凭据）、" +
                            "meta.txt（设备与版本信息）。",
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
private fun AsrSettingsDetailScreen(
    modifier: Modifier,
    panel: AsrSettingsPanel,
    state: SettingsViewModel.UiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val title = if (panel == AsrSettingsPanel.CLOUD) {
        "云端语音识别"
    } else {
        "本地模型"
    }
    val description = if (panel == AsrSettingsPanel.CLOUD) {
        "智谱 GLM-ASR 的 API 与接口设置"
    } else {
        "模型选择、识别语言、安装与更新"
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item {
            SettingsDetailHeader(
                title = title,
                description = description,
                onBack = onBack,
            )
        }
        when (panel) {
            AsrSettingsPanel.CLOUD -> {
                item {
                    ApiKeySettingItem(
                        title = "智谱 GLM API Key",
                        description = "仅用于云端语音识别；已内置密钥时可留空",
                        placeholder = "请输入您的智谱 API Key",
                        value = state.glmApiKey,
                        isConfigured = state.glmApiKey.isNotBlank(),
                        onValueChange = viewModel::updateGlmApiKey,
                        onTest = viewModel::testGlmConnection,
                        testStatus = state.glmTestStatus,
                        helpUrl = "https://bigmodel.cn/usercenter/apikeys",
                    )
                }
                item {
                    ExpandableSection("云端识别高级设置") {
                        ServiceBaseUrlField(
                            value = state.glmBaseUrl,
                            onValueChange = viewModel::updateGlmBaseUrl,
                            label = "GLM Base URL",
                        )
                    }
                }
            }

            AsrSettingsPanel.LOCAL -> {
                item {
                    LocalAsrModelCard(
                        nativeLibReady = state.asrNativeLibReady,
                        localModels = state.asrLocalModels,
                        selectedModelId = state.asrLocalModelId,
                        onModelSelected = viewModel::setAsrLocalModel,
                    )
                }
                item {
                    LocalAsrLanguageCard(
                        isQwen = state.asrLocalModelId == AsrModelUrls.QWEN3_ASR_ID,
                        mode = state.asrLanguageMode,
                        manualLanguage = state.asrManualLanguage,
                        useItn = state.asrSenseVoiceUseItn,
                        onModeSelected = viewModel::setAsrLanguageMode,
                        onManualLanguageSelected = viewModel::setAsrManualLanguage,
                        onUseItnChanged = viewModel::setAsrSenseVoiceUseItn,
                    )
                }
                item {
                    val context = LocalContext.current
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
                        onCheckModelUpdate = viewModel::checkAsrModelUpdate,
                        onCancelDownload = { viewModel.cancelAsrDownload(context) },
                        onDeleteModel = viewModel::deleteAsrModel,
                    )
                }
                if (state.showAsrDownloadConfirm) {
                    item {
                        AsrDownloadConfirmDialog(
                            model = AsrModelUrls.modelById(state.asrLocalModelId),
                            onDismiss = viewModel::dismissAsrDownloadConfirm,
                            onConfirm = { viewModel.startAsrDownload(it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailHeader(
    title: String,
    description: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回设置",
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .semantics { heading() }
            .padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingGroupHeader(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .semantics { heading() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServiceBaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            "默认地址适合大多数用户。仅在使用代理或自部署服务时修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
        Column(modifier = Modifier.padding(16.dp).selectableGroup()) {
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
                        .selectable(
                            selected = current == value,
                            onClick = { onSelected(value) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = current == value, onClick = null)
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
