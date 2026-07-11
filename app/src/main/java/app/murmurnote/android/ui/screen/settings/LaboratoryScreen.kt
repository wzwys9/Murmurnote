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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    llmApiConfigured: Boolean,
    onSafeLexiconEnabledChange: (Boolean) -> Unit,
    onManageSafeLexicon: () -> Unit,
    onBack: () -> Unit,
) {
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
