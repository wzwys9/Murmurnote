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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.domain.correction.CorrectionMatchMode
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
                    Text(stringResource(R.string.lexicon_what_it_does), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.lexicon_description_modes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.lexicon_description_context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.lexicon_description_exact),
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
                Text(stringResource(R.string.lexicon_add))
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
                    llmApiConfigured = llmApiConfigured,
                    isUpdating = rule.id in state.updatingRuleIds,
                    onEnabledChange = { enabled ->
                        viewModel.setRuleEnabled(rule.id, enabled)
                    },
                    onChangeMatchMode = { viewModel.requestMatchModeChange(rule) },
                    onDelete = { viewModel.requestDelete(rule) },
                )
            }
        }
    }

    if (state.showAddDialog) {
        AddLexiconRuleDialog(
            observedText = state.observedDraft,
            replacementText = state.replacementDraft,
            matchMode = state.matchModeDraft,
            errorMessage = state.errorMessage,
            isSaving = state.isSaving,
            masterEnabled = masterEnabled,
            llmApiConfigured = llmApiConfigured,
            onObservedChange = viewModel::updateObservedDraft,
            onReplacementChange = viewModel::updateReplacementDraft,
            onMatchModeChange = viewModel::updateMatchModeDraft,
            onDismiss = viewModel::dismissAddDialog,
            onSave = viewModel::saveRule,
        )
    }

    state.pendingModeRule?.let { rule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMatchModeChange,
            title = { Text(stringResource(R.string.lexicon_change_mode_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "“${rule.observedText}” → “${rule.replacementText}”",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MatchModeSelector(
                        selected = state.matchModeDraft,
                        enabled = true,
                        llmApiConfigured = llmApiConfigured,
                        onSelected = viewModel::updateMatchModeDraft,
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmMatchModeChange) {
                    Text(stringResource(R.string.lexicon_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMatchModeChange) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.lexicon_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.lexicon_delete_description,
                        rule.observedText,
                        rule.replacementText,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.asr_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.action_cancel))
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
                contentDescription = stringResource(R.string.lexicon_back),
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(R.string.lexicon_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.lexicon_subtitle),
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
            Text(stringResource(R.string.lexicon_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.lexicon_empty_example),
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
    llmApiConfigured: Boolean,
    isUpdating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onChangeMatchMode: () -> Unit,
    onDelete: () -> Unit,
) {
    val changeModeDescription = stringResource(
        R.string.lexicon_change_mode_description,
        rule.observedText,
        rule.replacementText,
    )
    val switchDescription = stringResource(
        R.string.lexicon_switch_description,
        rule.observedText,
        rule.replacementText,
    )
    val deleteDescription = stringResource(
        R.string.lexicon_delete_description_a11y,
        rule.observedText,
        rule.replacementText,
    )
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = rule.matchMode.displayName(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = rule.matchMode.shortDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onChangeMatchMode,
                    enabled = !isUpdating,
                    modifier = Modifier.semantics {
                        contentDescription = changeModeDescription
                    },
                ) {
                    Text(stringResource(R.string.lexicon_change))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        !rule.isEnabled -> stringResource(R.string.lexicon_status_disabled)
                        !masterEnabled -> stringResource(R.string.lexicon_status_master_off)
                        rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM &&
                            !llmApiConfigured -> stringResource(R.string.lexicon_status_missing_api)
                        rule.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM ->
                            stringResource(R.string.lexicon_status_context)
                        else -> stringResource(R.string.lexicon_status_exact)
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
                        contentDescription = switchDescription
                    },
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    enabled = !isUpdating,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = deleteDescription,
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
    matchMode: CorrectionMatchMode,
    errorMessage: String?,
    isSaving: Boolean,
    masterEnabled: Boolean,
    llmApiConfigured: Boolean,
    onObservedChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onMatchModeChange: (CorrectionMatchMode) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lexicon_add)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.lexicon_add_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!masterEnabled) {
                    Text(
                        stringResource(R.string.lexicon_saved_master_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = observedText,
                    onValueChange = onObservedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.lexicon_observed_label)) },
                    placeholder = { Text(stringResource(R.string.lexicon_observed_example)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = replacementText,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.lexicon_replacement_label)) },
                    placeholder = { Text(stringResource(R.string.lexicon_replacement_example)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Text(
                    stringResource(R.string.lexicon_mode_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                MatchModeSelector(
                    selected = matchMode,
                    enabled = !isSaving,
                    llmApiConfigured = llmApiConfigured,
                    onSelected = onMatchModeChange,
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
                    Text(stringResource(R.string.lexicon_saving))
                } else {
                    Text(stringResource(R.string.lexicon_save_enable))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MatchModeSelector(
    selected: CorrectionMatchMode,
    enabled: Boolean,
    llmApiConfigured: Boolean,
    onSelected: (CorrectionMatchMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            CorrectionMatchMode.CONTEXTUAL_LLM,
            CorrectionMatchMode.EXACT_TEXT,
        ).forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(mode) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = null,
                    enabled = enabled,
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(mode.displayName(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = when (mode) {
                            CorrectionMatchMode.CONTEXTUAL_LLM -> if (llmApiConfigured) {
                                stringResource(R.string.lexicon_context_recommended)
                            } else {
                                stringResource(R.string.lexicon_context_missing_api)
                            }
                            CorrectionMatchMode.EXACT_TEXT ->
                                stringResource(R.string.lexicon_exact_warning)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mode == CorrectionMatchMode.EXACT_TEXT) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CorrectionMatchMode.displayName(): String = when (this) {
    CorrectionMatchMode.CONTEXTUAL_LLM -> stringResource(R.string.lexicon_mode_context)
    CorrectionMatchMode.EXACT_TEXT -> stringResource(R.string.lexicon_mode_exact)
}

@Composable
private fun CorrectionMatchMode.shortDescription(): String = when (this) {
    CorrectionMatchMode.CONTEXTUAL_LLM -> stringResource(R.string.lexicon_mode_context_short)
    CorrectionMatchMode.EXACT_TEXT -> stringResource(R.string.lexicon_mode_exact_short)
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
                Text(stringResource(R.string.action_got_it))
            }
        }
    }
}
