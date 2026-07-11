package app.murmurnote.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.domain.correction.CorrectionRule

@Composable
internal fun SafeLexiconScreen(
    modifier: Modifier,
    masterEnabled: Boolean,
    llmApiConfigured: Boolean,
    onMasterEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: SafeLexiconViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            SafeLexiconHeader(onBack = onBack)
        }
        item {
            SafeLexiconMasterSwitchCard(
                enabled = masterEnabled,
                llmApiConfigured = llmApiConfigured,
                onEnabledChange = onMasterEnabledChange,
            )
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("它会做什么", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "完全在本地做“识别结果 → 正确写法”的精确替换，不调用 LLM，也不把词条注入识别模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "只影响开启后新完成的转写；实时预览、模型原文和历史内容都不会被改写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "后续 AI 纠错会以这些词条为术语基础，因此未配置 LLM API 时不能开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.errorMessage != null && !state.showAddDialog) {
            item {
                ErrorCard(
                    message = state.errorMessage,
                    onDismiss = viewModel::dismissError,
                )
            }
        }
        item {
            FilledTonalButton(
                onClick = viewModel::showAddDialog,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("添加精确词条")
            }
        }
        when {
            state.isLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }

            state.rules.isEmpty() -> item {
                EmptyLexiconCard()
            }

            else -> items(state.rules, key = { it.id }) { rule ->
                LexiconRuleCard(
                    rule = rule,
                    masterEnabled = masterEnabled,
                    isUpdating = rule.id in state.updatingRuleIds,
                    onEnabledChange = { enabled ->
                        viewModel.setRuleEnabled(rule.id, enabled)
                    },
                    onDelete = { viewModel.requestDelete(rule) },
                )
            }
        }
    }

    if (state.showAddDialog) {
        AddLexiconRuleDialog(
            observedText = state.observedDraft,
            replacementText = state.replacementDraft,
            errorMessage = state.errorMessage,
            isSaving = state.isSaving,
            masterEnabled = masterEnabled,
            onObservedChange = viewModel::updateObservedDraft,
            onReplacementChange = viewModel::updateReplacementDraft,
            onDismiss = viewModel::dismissAddDialog,
            onSave = viewModel::saveRule,
        )
    }

    state.pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除这个词条？") },
            text = {
                Text(
                    "“${rule.observedText}” → “${rule.replacementText}”\n\n" +
                        "删除后只会停止未来的替换，不会改动已经完成的转写或模型原文。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SafeLexiconHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回实验室功能",
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = "稳妥词本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "本地、精确、可随时关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLexiconCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("还没有词条", style = MaterialTheme.typography.titleMedium)
            Text(
                "例如：识别成“木木笔记”时，精确替换为“声记应用”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LexiconRuleCard(
    rule: CorrectionRule,
    masterEnabled: Boolean,
    isUpdating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${rule.observedText}  →  ${rule.replacementText}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        !rule.isEnabled -> "词条已停用"
                        !masterEnabled -> "词条已启用；总开关关闭"
                        else -> "会用于之后完成的转写"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !isUpdating,
                    modifier = Modifier.semantics {
                        contentDescription =
                            "词条开关：${rule.observedText}改为${rule.replacementText}"
                    },
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    enabled = !isUpdating,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription =
                            "删除词条：${rule.observedText}改为${rule.replacementText}",
                    )
                }
            }
        }
    }
}

@Composable
private fun AddLexiconRuleDialog(
    observedText: String,
    replacementText: String,
    errorMessage: String?,
    isSaving: Boolean,
    masterEnabled: Boolean,
    onObservedChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加精确词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "两边都需要 2–32 个字符。只有完全一致的文字才会替换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!masterEnabled) {
                    Text(
                        "保存后词条自身会启用；实验室里的总开关仍保持关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = observedText,
                    onValueChange = onObservedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("识别成什么") },
                    placeholder = { Text("例如：木木笔记") },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = replacementText,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("应该改成什么") },
                    placeholder = { Text("例如：声记应用") },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("保存中…")
                } else {
                    Text("保存并启用")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ErrorCard(
    message: String?,
    onDismiss: () -> Unit,
) {
    if (message == null) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    }
}
