package app.murmurnote.android.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.murmurnote.android.R
import app.murmurnote.android.data.asr.AsrLanguageMode
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.data.asr.AsrModelUrls
import app.murmurnote.android.data.asr.LocalAsrModelSpec

@Composable
internal fun AsrSettingsDirectoryCard(
    panel: AsrSettingsPanel,
    selected: Boolean,
    statusText: String? = null,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
) {
    val title = stringResource(
        if (panel == AsrSettingsPanel.CLOUD) R.string.asr_cloud_title
        else R.string.asr_local_title
    )
    val description = stringResource(
        if (panel == AsrSettingsPanel.CLOUD) R.string.asr_cloud_description
        else R.string.asr_local_description
    )
    val configureDescription = stringResource(R.string.asr_configure_description, title)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected,
                        onClick = onSelect,
                        role = Role.RadioButton,
                    )
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(
                    modifier = Modifier.padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            if (selected) R.string.asr_currently_used else R.string.asr_tap_to_select
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    statusText?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onConfigure,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { contentDescription = configureDescription },
            ) {
                Text(stringResource(R.string.asr_configure))
            }
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
internal fun LocalAsrLanguageCard(
    isQwen: Boolean,
    mode: AsrLanguageMode,
    manualLanguage: String,
    useItn: Boolean,
    onModeSelected: (AsrLanguageMode) -> Unit,
    onManualLanguageSelected: (String) -> Unit,
    onUseItnChanged: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.asr_language_title), style = MaterialTheme.typography.titleMedium)
            if (isQwen) {
                Text(
                    stringResource(R.string.asr_qwen_language_fixed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val modes = listOf(
                Triple(
                    AsrLanguageMode.SYSTEM,
                    stringResource(R.string.asr_language_system),
                    stringResource(R.string.asr_language_system_description),
                ),
                Triple(
                    AsrLanguageMode.AUTO,
                    stringResource(R.string.asr_language_auto),
                    stringResource(R.string.asr_language_auto_description),
                ),
                Triple(
                    AsrLanguageMode.MANUAL,
                    stringResource(R.string.asr_language_manual),
                    stringResource(R.string.asr_language_manual_description),
                )
            )
            Column(modifier = Modifier.selectableGroup()) {
                modes.forEach { (value, title, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == value,
                                onClick = { onModeSelected(value) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mode == value, onClick = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (mode == AsrLanguageMode.MANUAL) {
                ManualLanguageSelector(
                    selectedLanguage = manualLanguage,
                    onLanguageSelected = onManualLanguageSelected
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.asr_itn_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.asr_itn_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useItn, onCheckedChange = onUseItnChanged)
            }
        }
    }
}

@Composable
private fun ManualLanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    val options = listOf(
        "auto" to stringResource(R.string.asr_language_option_auto),
        "zh" to stringResource(R.string.asr_language_option_chinese),
        "en" to stringResource(R.string.asr_language_option_english),
        "ja" to stringResource(R.string.asr_language_option_japanese),
        "ko" to stringResource(R.string.asr_language_option_korean),
        "yue" to stringResource(R.string.asr_language_option_cantonese)
    )
    val selected = options.firstOrNull { it.first == selectedLanguage } ?: options.first()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected.second,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.asr_language_manual_label)) },
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
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onLanguageSelected(value)
                    },
                    leadingIcon = if (value == selected.first) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun NativeLibStatusRow(ready: Boolean) {
    val color = if (ready) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    val text = stringResource(
        if (ready) R.string.asr_native_ready else R.string.asr_native_missing
    )
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun LocalModelPicker(
    models: List<LocalAsrModelSpec>,
    selectedModelId: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            stringResource(R.string.asr_local_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        models.forEach { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedModelId == model.id,
                        onClick = { onSelected(model.id) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedModelId == model.id, onClick = null)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.asr_model_download_size,
                            localModelDescription(model),
                            model.sizeLabel,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun localModelDescription(model: LocalAsrModelSpec): String = when (model.id) {
    AsrModelUrls.SENSE_VOICE_ID -> stringResource(R.string.asr_model_sense_voice_description)
    AsrModelUrls.QWEN3_ASR_ID -> stringResource(R.string.asr_model_qwen_description)
    else -> model.displayName
}

@Composable
internal fun LocalModelStatusBlock(
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
                    Text(stringResource(R.string.asr_model_not_downloaded), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.asr_model_install_required, model.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (bundledAssetsAvailable) {
                        Text(
                            stringResource(R.string.asr_model_bundled_description, model.sizeLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onInstallBundledModel) {
                                Text(stringResource(R.string.asr_install_bundled))
                            }
                            OutlinedButton(onClick = onRequestDownload) {
                                Text(stringResource(R.string.asr_download_network))
                            }
                        }
                    } else {
                        MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                        Button(onClick = onRequestDownload) {
                            Text(stringResource(R.string.asr_download_model_size, model.sizeLabel))
                        }
                    }
                }
                is AsrModelManager.ModelStatus.Downloading -> {
                    Text(
                        stringResource(
                            R.string.asr_downloading_percent,
                            (status.progress * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(
                            R.string.asr_download_speed_eta,
                            formatSpeed(status.bytesPerSec),
                            formatEta(status.etaSec),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (status.bytesPerSec in 1..(50 * 1024)) {
                        Text(
                            stringResource(R.string.asr_download_slow),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                    OutlinedButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.asr_cancel_download))
                    }
                }
                is AsrModelManager.ModelStatus.Extracting -> {
                    Text(
                        stringResource(
                            R.string.asr_extracting_percent,
                            (status.progress * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is AsrModelManager.ModelStatus.Ready -> {
                    Text(
                        stringResource(R.string.asr_model_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4CAF50),
                    )
                    Text(
                        stringResource(R.string.asr_model_storage, formatSize(status.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (model.supportsFastConcurrency) {
                        ConcurrencySelector(localConcurrency, onConcurrencyChanged)
                    } else {
                        Text(
                            stringResource(R.string.asr_qwen_single_concurrency),
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
                            Text(
                                stringResource(
                                    if (updateChecking) R.string.asr_update_checking
                                    else R.string.asr_check_update
                                )
                            )
                        }
                        OutlinedButton(onClick = onDeleteModel) {
                            Text(stringResource(R.string.asr_delete_model))
                        }
                    }
                    if (updateCheck is AsrModelManager.ModelUpdateCheck.UpdateAvailable) {
                        Button(onClick = onRequestDownload) {
                            Text(stringResource(R.string.asr_download_update))
                        }
                    }
                }
                is AsrModelManager.ModelStatus.HashMismatch -> {
                    Text(
                        stringResource(R.string.asr_hash_mismatch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.asr_hash_mismatch_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRequestDownload) {
                        Text(stringResource(R.string.asr_redownload))
                    }
                }
                is AsrModelManager.ModelStatus.Corrupted -> {
                    Text(
                        stringResource(R.string.asr_model_corrupted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.asr_model_corrupted_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestDownload) {
                            Text(stringResource(R.string.asr_redownload))
                        }
                        OutlinedButton(onClick = onDeleteModel) {
                            Text(stringResource(R.string.asr_delete))
                        }
                    }
                }
                is AsrModelManager.ModelStatus.Failed -> {
                    Text(
                        stringResource(R.string.asr_download_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.asr_download_failed_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MirrorPicker(mirrorIndex, mirrorOptions, onMirrorSelected)
                    Button(onClick = onRequestDownload) { Text(stringResource(R.string.action_retry)) }
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
            stringResource(R.string.asr_update_not_installed) to
                MaterialTheme.colorScheme.onSurfaceVariant
        is AsrModelManager.ModelUpdateCheck.UpToDate ->
            stringResource(R.string.asr_update_current) to Color(0xFF4CAF50)
        is AsrModelManager.ModelUpdateCheck.UpdateAvailable ->
            stringResource(R.string.asr_update_available) to MaterialTheme.colorScheme.primary
        is AsrModelManager.ModelUpdateCheck.UnableToCheck ->
            stringResource(R.string.asr_update_unavailable) to MaterialTheme.colorScheme.error
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
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            stringResource(R.string.asr_download_source),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        options.forEachIndexed { i, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == i,
                        onClick = { onSelected(i) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = current == i, onClick = null)
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
            stringResource(R.string.asr_concurrency, current),
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
        title = { Text(stringResource(R.string.asr_download_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.asr_download_dialog_description,
                    model.displayName,
                    model.sizeLabel,
                )
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(ctx) }) { Text(stringResource(R.string.asr_start_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
