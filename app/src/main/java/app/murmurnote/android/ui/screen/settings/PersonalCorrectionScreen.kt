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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.domain.correction.PersonalCorrectionLearningState
import app.murmurnote.android.domain.correction.PersonalCorrectionProfile
import app.murmurnote.android.domain.correction.PersonalLearningConfidence
import app.murmurnote.android.domain.correction.PersonalLearningVerdict
import app.murmurnote.android.domain.correction.PinyinRelation

@Composable
internal fun PersonalCorrectionScreen(
    modifier: Modifier,
    masterEnabled: Boolean,
    onBack: () -> Unit,
    viewModel: PersonalCorrectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { PersonalCorrectionHeader(onBack) }
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
                    Text(
                        stringResource(
                            if (masterEnabled) R.string.personal_master_enabled
                            else R.string.personal_master_disabled
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.personal_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::dismissError) {
                            Text(stringResource(R.string.action_got_it))
                        }
                    }
                }
            }
        }
        if (state.profiles.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = viewModel::requestClearAll,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.personal_clear_all))
                }
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
            state.profiles.isEmpty() -> item { PersonalCorrectionEmptyCard() }
            else -> items(state.profiles, key = { it.ruleId }) { profile ->
                PersonalCorrectionProfileCard(
                    profile = profile,
                    masterEnabled = masterEnabled,
                    isUpdating = profile.ruleId in state.updatingRuleIds,
                    onEnabledChange = { viewModel.setEnabled(profile, it) },
                    onDelete = { viewModel.requestDelete(profile) },
                )
            }
        }
    }

    state.pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.personal_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.personal_delete_description,
                        profile.observedText,
                        profile.replacementText,
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
    if (state.confirmClearAll) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAll,
            title = { Text(stringResource(R.string.personal_clear_title)) },
            text = { Text(stringResource(R.string.personal_clear_description)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClearAll) {
                    Text(stringResource(R.string.personal_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearAll) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PersonalCorrectionHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.personal_back),
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                stringResource(R.string.personal_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.personal_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalCorrectionEmptyCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.personal_empty), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.personal_empty_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalCorrectionProfileCard(
    profile: PersonalCorrectionProfile,
    masterEnabled: Boolean,
    isUpdating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val switchDescription = stringResource(
        R.string.personal_switch_description,
        profile.observedText,
        profile.replacementText,
    )
    val deleteDescription = stringResource(
        R.string.personal_delete_description_a11y,
        profile.observedText,
        profile.replacementText,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${profile.observedText}  →  ${profile.replacementText}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${profile.state.displayName()} · ${profile.pinyinRelation.displayName()} · " +
                    stringResource(
                        R.string.personal_positive_evidence,
                        profile.positiveEvidenceCount,
                    ) +
                    if (profile.negativeEvidenceCount > 0) {
                        stringResource(
                            R.string.personal_negative_evidence,
                            profile.negativeEvidenceCount,
                        )
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.reviewExplanation()?.let { explanation ->
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        profile.isEnabled && masterEnabled ->
                            stringResource(R.string.personal_active_status)
                        profile.isEnabled -> stringResource(R.string.personal_enabled_master_off)
                        profile.canBeEnabled -> stringResource(R.string.personal_can_enable)
                        else -> stringResource(R.string.personal_inactive_status)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !isUpdating && (profile.isEnabled || profile.canBeEnabled),
                    modifier = Modifier.semantics {
                        contentDescription = switchDescription
                    },
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, enabled = !isUpdating) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = deleteDescription,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalCorrectionLearningState.displayName(): String = when (this) {
    PersonalCorrectionLearningState.PENDING_REVIEW -> stringResource(R.string.personal_state_pending)
    PersonalCorrectionLearningState.ACTIVE -> stringResource(R.string.personal_state_active)
    PersonalCorrectionLearningState.NEEDS_MORE_EVIDENCE -> stringResource(R.string.personal_state_more_evidence)
    PersonalCorrectionLearningState.REJECTED -> stringResource(R.string.personal_state_rejected)
    PersonalCorrectionLearningState.DISABLED -> stringResource(R.string.personal_state_disabled)
}

@Composable
private fun PinyinRelation.displayName(): String = when (this) {
    PinyinRelation.EXACT_PINYIN -> stringResource(R.string.personal_pinyin_exact)
    PinyinRelation.NEAR_PINYIN -> stringResource(R.string.personal_pinyin_near)
    PinyinRelation.NOT_PHONETIC -> stringResource(R.string.personal_pinyin_not_phonetic)
    PinyinRelation.UNAVAILABLE -> stringResource(R.string.personal_pinyin_unavailable)
}

@Composable
private fun PersonalCorrectionProfile.reviewExplanation(): String? {
    val verdictText = when (lastVerdict ?: return null) {
        PersonalLearningVerdict.ACTIVATE -> stringResource(R.string.personal_verdict_activate)
        PersonalLearningVerdict.NEEDS_MORE_EVIDENCE -> stringResource(R.string.personal_verdict_more_evidence)
        PersonalLearningVerdict.REJECT -> stringResource(R.string.personal_verdict_reject)
    }
    val confidenceText = when (lastConfidence) {
        PersonalLearningConfidence.HIGH -> stringResource(R.string.personal_confidence_high)
        PersonalLearningConfidence.MEDIUM -> stringResource(R.string.personal_confidence_medium)
        PersonalLearningConfidence.LOW -> stringResource(R.string.personal_confidence_low)
        null -> stringResource(R.string.personal_confidence_unknown)
    }
    val reasonText = when (lastReasonCode) {
        "PHONETIC_ASR_ERROR" -> stringResource(R.string.personal_reason_phonetic)
        "USER_TERM_FITS_CONTEXT" -> stringResource(R.string.personal_reason_user_term)
        "PROPER_NOUN_FITS_CONTEXT" -> stringResource(R.string.personal_reason_proper_noun)
        "VISUAL_SIMILARITY_ONLY" -> stringResource(R.string.personal_reason_visual)
        "NOT_AN_ASR_ERROR" -> stringResource(R.string.personal_reason_not_asr)
        "AMBIGUOUS_CONTEXT" -> stringResource(R.string.personal_reason_ambiguous)
        "LOCAL_RULE_CYCLE" -> stringResource(R.string.personal_reason_cycle)
        "LOCAL_ACTIVE_RULE_LIMIT" -> stringResource(R.string.personal_reason_limit)
        "LOCAL_USER_DICTIONARY_CONFLICT" ->
            stringResource(R.string.personal_reason_dictionary_conflict)
        else -> stringResource(R.string.personal_reason_unknown)
    }
    return stringResource(
        R.string.personal_latest_review,
        reasonText,
        confidenceText,
        verdictText,
    )
}
