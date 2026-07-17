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
import androidx.compose.ui.res.stringResource
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
import app.murmurnote.android.R
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
    var showPersonalCorrection by rememberSaveable { mutableStateOf(false) }

    BackHandler(
        enabled = showPersonalCorrection || showSafeLexicon || showLaboratory ||
            asrSettingsDetail != null,
    ) {
        when {
            showPersonalCorrection -> showPersonalCorrection = false
            showSafeLexicon -> showSafeLexicon = false
            showLaboratory -> showLaboratory = false
            else -> asrSettingsDetail = null
        }
    }
    if (showPersonalCorrection) {
        PersonalCorrectionScreen(
            modifier = modifier,
            masterEnabled = state.personalCorrectionEnabled && state.llmApiKey.isNotBlank(),
            onBack = { showPersonalCorrection = false },
        )
        return
    }
    if (showSafeLexicon) {
        SafeLexiconScreen(
            modifier = modifier,
            masterEnabled = state.safeLexiconEnabled,
            llmApiConfigured = state.llmApiKey.isNotBlank(),
            onMasterEnabledChange = viewModel::setSafeLexiconEnabled,
            onBack = { showSafeLexicon = false },
        )
        return
    }
    if (showLaboratory) {
        LaboratoryScreen(
            modifier = modifier,
            safeLexiconEnabled = state.safeLexiconEnabled,
            personalCorrectionEnabled =
                state.personalCorrectionEnabled && state.llmApiKey.isNotBlank(),
            personalCorrectionDisclosureAccepted =
                state.personalCorrectionDisclosureAccepted,
            llmApiConfigured = state.llmApiKey.isNotBlank(),
            onSafeLexiconEnabledChange = viewModel::setSafeLexiconEnabled,
            onManageSafeLexicon = { showSafeLexicon = true },
            onPersonalCorrectionEnabledChange = viewModel::setPersonalCorrectionEnabled,
            onAcceptPersonalCorrectionDisclosure =
                viewModel::acceptDisclosureAndEnablePersonalCorrection,
            onManagePersonalCorrection = { showPersonalCorrection = true },
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
        item { SettingSectionHeader(stringResource(R.string.settings_section_general)) }
        item { AppLanguageSetting() }

        item { SettingSectionHeader(stringResource(R.string.settings_section_speech)) }
        item {
            AsrSettingsDirectoryCard(
                panel = AsrSettingsPanel.CLOUD,
                selected = asrSettingsPanel == AsrSettingsPanel.CLOUD,
                statusText = asrSettingsStatus(
                    panel = AsrSettingsPanel.CLOUD,
                    selected = asrSettingsPanel == AsrSettingsPanel.CLOUD,
                    cloudApiConfigured = state.glmApiKey.isNotBlank(),
                    localModelDisplayName = AsrModelUrls.modelById(
                        state.asrLocalModelId,
                    ).displayName,
                ).displayText(),
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
                statusText = asrSettingsStatus(
                    panel = AsrSettingsPanel.LOCAL,
                    selected = asrSettingsPanel == AsrSettingsPanel.LOCAL,
                    cloudApiConfigured = state.glmApiKey.isNotBlank(),
                    localModelDisplayName = AsrModelUrls.modelById(
                        state.asrLocalModelId,
                    ).displayName,
                ).displayText(),
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
                title = stringResource(R.string.settings_recording_processing),
                description = stringResource(R.string.settings_recording_processing_description)
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

        item { SettingSectionHeader(stringResource(R.string.settings_section_ai)) }
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
                    title = stringResource(R.string.settings_api_key_title, llmProvider.displayName),
                    description = stringResource(R.string.settings_llm_api_description),
                    placeholder = stringResource(
                        R.string.settings_llm_api_placeholder,
                        llmProvider.displayName,
                    ),
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
                ExpandableSection(stringResource(R.string.settings_ai_advanced)) {
                    ServiceBaseUrlField(
                        value = state.llmBaseUrl,
                        onValueChange = viewModel::updateLlmBaseUrl,
                        label = "${llmProvider.displayName} Base URL"
                    )
                }
            }
        }

        item { SettingSectionHeader(stringResource(R.string.settings_section_more)) }
        item {
            LaboratoryDirectoryCard(
                onOpen = { showLaboratory = true },
            )
        }

        item { SettingSectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
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

        item { SettingSectionHeader(stringResource(R.string.settings_section_logs)) }
        item {
            val ctx = LocalContext.current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_runtime_logs), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_runtime_logs_description),
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
                                Text(stringResource(R.string.settings_processing))
                            } else Text(stringResource(R.string.settings_export_downloads))
                        }
                        OutlinedButton(
                            onClick = { viewModel.shareLog(ctx) },
                            enabled = !state.exportingLog
                        ) {
                            Text(stringResource(R.string.settings_share))
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
private fun AsrSettingsStatus?.displayText(): String? = when (this) {
    is AsrSettingsStatus.Cloud -> stringResource(
        if (configured) R.string.asr_api_configured else R.string.asr_api_not_configured
    )
    is AsrSettingsStatus.Local -> stringResource(R.string.asr_current_model, modelDisplayName)
    null -> null
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
        stringResource(R.string.asr_cloud_title)
    } else {
        stringResource(R.string.asr_local_title)
    }
    val description = if (panel == AsrSettingsPanel.CLOUD) {
        stringResource(R.string.settings_cloud_detail_description)
    } else {
        stringResource(R.string.settings_local_detail_description)
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
                        title = stringResource(R.string.settings_glm_api_title),
                        description = stringResource(R.string.settings_glm_api_description),
                        placeholder = stringResource(R.string.settings_glm_api_placeholder),
                        value = state.glmApiKey,
                        isConfigured = state.glmApiKey.isNotBlank(),
                        onValueChange = viewModel::updateGlmApiKey,
                        onTest = viewModel::testGlmConnection,
                        testStatus = state.glmTestStatus,
                        helpUrl = "https://bigmodel.cn/usercenter/apikeys",
                    )
                }
                item {
                    ExpandableSection(stringResource(R.string.settings_cloud_advanced)) {
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
                        mirrorOptions = listOf(
                            stringResource(R.string.asr_mirror_github),
                            stringResource(R.string.asr_mirror_ghproxy),
                            stringResource(R.string.asr_mirror_gh_proxy),
                        ),
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
                contentDescription = stringResource(R.string.settings_back),
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
                    Text(stringResource(R.string.settings_update_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_update_description),
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
                        Text(stringResource(R.string.settings_checking))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_check))
                    }
                }
            }

            when (status) {
                AppUpdateStatus.Idle -> Unit
                AppUpdateStatus.Checking -> Text(
                    stringResource(R.string.settings_update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is AppUpdateStatus.UpToDate -> Text(
                    stringResource(R.string.settings_update_current, status.latestVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
                is AppUpdateStatus.UpdateAvailable -> {
                    Text(
                        stringResource(R.string.settings_update_available, status.latestVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, status.releaseUrl.toUri()))
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_open_download))
                    }
                }
                is AppUpdateStatus.Failed -> Text(
                    stringResource(R.string.settings_update_failed, status.message),
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
    val options = listOf(
        "OFF" to (
            stringResource(R.string.settings_performance_off) to
                stringResource(R.string.settings_performance_off_description)
            ),
        "POWER_SAVE" to (
            stringResource(R.string.settings_performance_power_save) to
                stringResource(R.string.settings_performance_power_save_description)
            ),
        "BALANCED" to (
            stringResource(R.string.settings_performance_balanced) to
                stringResource(R.string.settings_performance_balanced_description)
            ),
        "FAST" to (
            stringResource(R.string.settings_performance_fast) to
                stringResource(R.string.settings_performance_fast_description)
            )
    )
    val selected = options.firstOrNull { it.first == mode } ?: options.first { it.first == "BALANCED" }
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_performance_title), style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selected.second.first,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_performance_mode)) },
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_low_battery),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = lowBatteryProtection,
                        onCheckedChange = onLowBatteryProtectionChanged
                    )
                }
                Text(
                    stringResource(R.string.settings_low_battery_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
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
            stringResource(R.string.settings_base_url_description),
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
                            } else Text(stringResource(R.string.settings_test_connection))
                        }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, helpUrl.toUri()))
                        }) { Text(stringResource(R.string.settings_get_api_key)) }
                    }
                    when (val ts = testStatus) {
                        is TestStatus.Success -> Text(
                            stringResource(R.string.settings_connection_success),
                            color = Color(0xFF4CAF50),
                        )
                        is TestStatus.Failed -> Text(
                            stringResource(R.string.settings_connection_failed, ts.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(configured: Boolean) {
    val text = stringResource(
        if (configured) R.string.settings_configured else R.string.settings_not_configured
    )
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
                Text(stringResource(R.string.settings_ai_extraction_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (enabled) R.string.settings_ai_extraction_enabled
                        else R.string.settings_ai_extraction_disabled
                    ),
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
            Text(stringResource(R.string.settings_official_mode), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_official_mode_description),
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
                    label = { Text(stringResource(R.string.settings_current_official_mode)) },
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
                    Text(
                        stringResource(R.string.settings_provider_models, provider.displayName),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.settings_provider_models_description,
                            provider.displayName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_refresh),
                    )
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
                    label = {
                        Text(
                            stringResource(
                                if (currentModel.isBlank()) R.string.settings_select_model
                                else R.string.settings_current_model
                            )
                        )
                    },
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
                            text = { Text(stringResource(R.string.settings_refresh_models_first)) },
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
                    stringResource(R.string.settings_models_failed, error),
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
        Triple(
            "none",
            stringResource(R.string.settings_reasoning_none),
            stringResource(R.string.settings_reasoning_none_description),
        ),
        Triple(
            "high",
            stringResource(R.string.settings_reasoning_high),
            stringResource(R.string.settings_reasoning_high_description),
        ),
        Triple(
            "max",
            stringResource(R.string.settings_reasoning_max),
            stringResource(R.string.settings_reasoning_max_description),
        )
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp).selectableGroup()) {
            Text(stringResource(R.string.settings_reasoning_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_reasoning_description),
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
