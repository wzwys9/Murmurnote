package app.murmurnote.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun LaboratoryDirectoryCard(
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("实验室功能", style = MaterialTheme.typography.titleMedium)
                Text(
                    "体验仍在验证中的功能；所有实验默认关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.semantics {
                    contentDescription = "进入实验室功能"
                },
            ) {
                Text("进入")
            }
        }
    }
}

@Composable
internal fun LaboratoryScreen(
    modifier: Modifier,
    safeLexiconEnabled: Boolean,
    personalCorrectionEnabled: Boolean,
    personalCorrectionDisclosureAccepted: Boolean,
    llmApiConfigured: Boolean,
    onSafeLexiconEnabledChange: (Boolean) -> Unit,
    onManageSafeLexicon: () -> Unit,
    onPersonalCorrectionEnabledChange: (Boolean) -> Unit,
    onAcceptPersonalCorrectionDisclosure: () -> Unit,
    onManagePersonalCorrection: () -> Unit,
    onBack: () -> Unit,
) {
    var showPersonalCorrectionDisclosure by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            LaboratoryHeader(onBack = onBack)
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "实验功能可能继续调整。每项功能都有独立开关，关闭后不会参与处理。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SafeLexiconMasterSwitchCard(
                enabled = safeLexiconEnabled,
                llmApiConfigured = llmApiConfigured,
                onEnabledChange = onSafeLexiconEnabledChange,
                onManage = onManageSafeLexicon,
            )
        }
        item {
            PersonalCorrectionMasterSwitchCard(
                enabled = personalCorrectionEnabled,
                llmApiConfigured = llmApiConfigured,
                onEnabledChange = { requested ->
                    when {
                        !requested -> onPersonalCorrectionEnabledChange(false)
                        personalCorrectionDisclosureAccepted ->
                            onPersonalCorrectionEnabledChange(true)
                        else -> showPersonalCorrectionDisclosure = true
                    }
                },
                onManage = onManagePersonalCorrection,
            )
        }
    }

    if (showPersonalCorrectionDisclosure) {
        AlertDialog(
            onDismissRequest = { showPersonalCorrectionDisclosure = false },
            title = { Text("开启个性化自学习纠错？") },
            text = {
                Text(
                    "开启后，你手动修改转写时，系统会把修改词对和附近最多 240 个字符" +
                        "发送给当前配置的大模型，用于判断是否值得学习。\n\n" +
                        "未来命中已学词条时，也只发送候选附近的局部文字；不会发送音频、" +
                        "标题、总结或整段录音。所有自动替换都可以停用或删除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPersonalCorrectionDisclosure = false
                        onAcceptPersonalCorrectionDisclosure()
                    },
                ) {
                    Text("同意并开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPersonalCorrectionDisclosure = false }) {
                    Text("暂不开启")
                }
            },
        )
    }
}

@Composable
private fun PersonalCorrectionMasterSwitchCard(
    enabled: Boolean,
    llmApiConfigured: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onManage: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = enabled,
                        enabled = llmApiConfigured,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    )
                    .semantics { contentDescription = "个性化自学习纠错总开关" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("个性化自学习纠错（实验）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            !llmApiConfigured ->
                                "不可开启：请先配置当前大模型的 API Key。"
                            enabled ->
                                "已开启：从你的转写修改中学习，未来按上下文谨慎纠错。"
                            else ->
                                "已关闭：不采集、不调用纠错模型，也不应用已学词条。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled && llmApiConfigured,
                    onCheckedChange = null,
                    enabled = llmApiConfigured,
                )
            }
            OutlinedButton(
                onClick = onManage,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("管理学习记录")
            }
        }
    }
}

@Composable
internal fun SafeLexiconMasterSwitchCard(
    enabled: Boolean,
    llmApiConfigured: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onManage: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = enabled,
                        enabled = llmApiConfigured,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    )
                    .semantics {
                        contentDescription = "稳妥词本总开关"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("稳妥词本（实验）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            !llmApiConfigured ->
                                "不可开启：请先在“AI 文本整理”中配置当前大模型的 API Key。"
                            enabled ->
                                "已开启：启用的精确词条会用于之后完成的转写。"
                            else ->
                                "已关闭：不加载、不应用词条；已保存词条仍会保留。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled && llmApiConfigured,
                    onCheckedChange = null,
                    enabled = llmApiConfigured,
                )
            }
            if (onManage != null) {
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("管理词条")
                }
            }
        }
    }
}

@Composable
private fun LaboratoryHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
                text = "实验室功能",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "选择性体验仍在验证中的功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
