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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.AsrLanguageMode
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.data.asr.LocalAsrModelSpec

private data class AsrEngineOption(
    val panel: AsrSettingsPanel,
    val label: String,
    val description: String,
    val engineType: AsrEngineType
)

@Composable
fun AsrEngineSelectorCard(
    engineType: String,
    onEngineSelected: (String) -> Unit
) {
    val selectedPanel = resolveAsrSettingsPanel(engineType)
    val options = listOf(
        AsrEngineOption(
            panel = AsrSettingsPanel.CLOUD,
            label = "云端识别",
            description = "智谱 GLM-ASR，需要网络、API Key，并可能产生调用费用。",
            engineType = AsrEngineType.CLOUD_GLM
        ),
        AsrEngineOption(
            panel = AsrSettingsPanel.LOCAL,
            label = "本地模型",
            description = "使用 SenseVoiceSmall 或 Qwen3-ASR，模型就绪后可离线运行。",
            engineType = AsrEngineType.LOCAL_SENSE_VOICE
        )
    )

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("识别引擎", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        ),
                        onClick = { onEngineSelected(option.engineType.name) },
                        selected = option.panel == selectedPanel,
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                options.first { it.panel == selectedPanel }.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "切换引擎不会清空另一套已保存的配置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
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
            Text("识别语言", style = MaterialTheme.typography.titleMedium)
            if (isQwen) {
                Text(
                    "Qwen3-ASR 固定使用自动语言识别，无需额外设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val modes = listOf(
                Triple(AsrLanguageMode.SYSTEM, "跟随系统", "中文系统使用中文，英文系统使用英文，其他语言自动识别"),
                Triple(AsrLanguageMode.AUTO, "自动识别", "让 SenseVoice 自动判断语种"),
                Triple(AsrLanguageMode.MANUAL, "手动指定", "从支持的语言中固定选择")
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
                    Text("标点与数字规范化（ITN）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "将口语中的数字、日期等整理为更易读的文本，默认开启。",
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
        "auto" to "自动",
        "zh" to "中文",
        "en" to "英语",
        "ja" to "日语",
        "ko" to "韩语",
        "yue" to "粤语"
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
            label = { Text("指定语言") },
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
    val text = if (ready) "✓ sherpa-onnx 原生库已集成"
        else "✗ sherpa-onnx 原生库未集成（开发者需集成 Kotlin 绑定和 JNI 库后重新构建；模型文件即使下完也无法运行）"
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
            "本地模型",
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
    Column(modifier = Modifier.selectableGroup()) {
        Text(
            "下载源",
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
