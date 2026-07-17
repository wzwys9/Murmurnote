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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.murmurnote.android.R

@Composable
internal fun LaboratoryDirectoryCard(
    onOpen: () -> Unit,
) {
    val openDescription = stringResource(R.string.laboratory_open_description)
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
                Text(stringResource(R.string.laboratory_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.laboratory_directory_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.semantics {
                    contentDescription = openDescription
                },
            ) {
                Text(stringResource(R.string.laboratory_open))
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
                    text = stringResource(R.string.laboratory_notice),
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
            title = { Text(stringResource(R.string.laboratory_personal_disclosure_title)) },
            text = {
                Text(stringResource(R.string.laboratory_personal_disclosure))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPersonalCorrectionDisclosure = false
                        onAcceptPersonalCorrectionDisclosure()
                    },
                ) {
                    Text(stringResource(R.string.laboratory_accept_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPersonalCorrectionDisclosure = false }) {
                    Text(stringResource(R.string.laboratory_not_now))
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
    val switchDescription = stringResource(R.string.laboratory_personal_switch_description)
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
                    .semantics { contentDescription = switchDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.laboratory_personal_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            !llmApiConfigured ->
                                stringResource(R.string.laboratory_personal_missing_key)
                            enabled ->
                                stringResource(R.string.laboratory_personal_enabled)
                            else ->
                                stringResource(R.string.laboratory_personal_disabled)
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
                Text(stringResource(R.string.laboratory_manage_learning))
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
    val switchDescription = stringResource(R.string.laboratory_lexicon_switch_description)
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
                        enabled = true,
                        role = Role.Switch,
                        onValueChange = onEnabledChange,
                    )
                    .semantics {
                        contentDescription = switchDescription
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.laboratory_lexicon_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            enabled && !llmApiConfigured ->
                                stringResource(R.string.laboratory_lexicon_waiting_for_key)
                            enabled ->
                                stringResource(R.string.laboratory_lexicon_enabled)
                            else ->
                                stringResource(R.string.laboratory_lexicon_disabled)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
                    enabled = true,
                )
            }
            if (onManage != null) {
                OutlinedButton(
                    onClick = onManage,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.laboratory_manage_lexicon))
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
                contentDescription = stringResource(R.string.laboratory_back_to_settings),
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(R.string.laboratory_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.laboratory_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
