package app.murmurnote.android.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.repository.TranscriptRepository
import app.murmurnote.android.R
import app.murmurnote.android.domain.correction.ContextualCorrectionCapacityExceededException
import app.murmurnote.android.domain.correction.CorrectionMatchMode
import app.murmurnote.android.domain.correction.CorrectionRule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SafeLexiconViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptRepository: TranscriptRepository,
) : ViewModel() {

    data class UiState(
        val rules: List<CorrectionRule> = emptyList(),
        val isLoading: Boolean = true,
        val showAddDialog: Boolean = false,
        val observedDraft: String = "",
        val replacementDraft: String = "",
        val matchModeDraft: CorrectionMatchMode = CorrectionMatchMode.CONTEXTUAL_LLM,
        val isSaving: Boolean = false,
        val updatingRuleIds: Set<String> = emptySet(),
        val pendingModeRule: CorrectionRule? = null,
        val pendingDeleteRule: CorrectionRule? = null,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transcriptRepository.observeUserDefinedRules().collect { rules ->
                _uiState.update { it.copy(rules = rules, isLoading = false) }
            }
        }
    }

    fun showAddDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = true,
                observedDraft = "",
                replacementDraft = "",
                matchModeDraft = CorrectionMatchMode.CONTEXTUAL_LLM,
                errorMessage = null,
            )
        }
    }

    fun dismissAddDialog() {
        if (_uiState.value.isSaving) return
        _uiState.update {
            it.copy(
                showAddDialog = false,
                observedDraft = "",
                replacementDraft = "",
                matchModeDraft = CorrectionMatchMode.CONTEXTUAL_LLM,
                errorMessage = null,
            )
        }
    }

    fun updateObservedDraft(value: String) {
        _uiState.update { it.copy(observedDraft = value, errorMessage = null) }
    }

    fun updateReplacementDraft(value: String) {
        _uiState.update { it.copy(replacementDraft = value, errorMessage = null) }
    }

    fun updateMatchModeDraft(value: CorrectionMatchMode) {
        _uiState.update { it.copy(matchModeDraft = value, errorMessage = null) }
    }

    fun saveRule() {
        val snapshot = _uiState.value
        if (snapshot.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                transcriptRepository.saveUserDefinedRule(
                    observedText = snapshot.observedDraft,
                    replacementText = snapshot.replacementDraft,
                    matchMode = snapshot.matchModeDraft,
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        observedDraft = "",
                        replacementDraft = "",
                        matchModeDraft = CorrectionMatchMode.CONTEXTUAL_LLM,
                        isSaving = false,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = error.toUserMessage())
                }
            }
        }
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        if (ruleId in _uiState.value.updatingRuleIds) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updatingRuleIds = it.updatingRuleIds + ruleId,
                    errorMessage = null,
                )
            }
            runCatching {
                transcriptRepository.setUserDefinedRuleEnabled(ruleId, enabled)
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.toUserMessage()) }
            }
            _uiState.update {
                it.copy(updatingRuleIds = it.updatingRuleIds - ruleId)
            }
        }
    }

    fun requestMatchModeChange(rule: CorrectionRule) {
        _uiState.update {
            it.copy(
                pendingModeRule = rule,
                matchModeDraft = rule.matchMode,
                errorMessage = null,
            )
        }
    }

    fun dismissMatchModeChange() {
        _uiState.update {
            it.copy(
                pendingModeRule = null,
                matchModeDraft = CorrectionMatchMode.CONTEXTUAL_LLM,
            )
        }
    }

    fun confirmMatchModeChange() {
        val state = _uiState.value
        val rule = state.pendingModeRule ?: return
        if (rule.id in state.updatingRuleIds) return
        if (rule.matchMode == state.matchModeDraft) {
            dismissMatchModeChange()
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingModeRule = null,
                    updatingRuleIds = it.updatingRuleIds + rule.id,
                    errorMessage = null,
                )
            }
            runCatching {
                transcriptRepository.setUserDefinedRuleMatchMode(
                    ruleId = rule.id,
                    matchMode = state.matchModeDraft,
                )
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.toUserMessage()) }
            }
            _uiState.update {
                it.copy(
                    updatingRuleIds = it.updatingRuleIds - rule.id,
                    matchModeDraft = CorrectionMatchMode.CONTEXTUAL_LLM,
                )
            }
        }
    }

    fun requestDelete(rule: CorrectionRule) {
        _uiState.update { it.copy(pendingDeleteRule = rule, errorMessage = null) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(pendingDeleteRule = null) }
    }

    fun confirmDelete() {
        val rule = _uiState.value.pendingDeleteRule ?: return
        if (rule.id in _uiState.value.updatingRuleIds) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingDeleteRule = null,
                    updatingRuleIds = it.updatingRuleIds + rule.id,
                    errorMessage = null,
                )
            }
            runCatching {
                transcriptRepository.deleteUserDefinedRule(rule.id)
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.toUserMessage()) }
            }
            _uiState.update {
                it.copy(updatingRuleIds = it.updatingRuleIds - rule.id)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun Throwable.toUserMessage(): String {
        val presentation = lexiconErrorPresentation(this)
        return context.getString(
            presentation.messageResource,
            *presentation.formatArguments.toTypedArray(),
        )
    }
}

internal data class LexiconErrorPresentation(
    val messageResource: Int,
    val formatArguments: List<Any> = emptyList(),
)

internal fun lexiconErrorPresentation(error: Throwable): LexiconErrorPresentation =
    when (error) {
        is ContextualCorrectionCapacityExceededException -> LexiconErrorPresentation(
            messageResource = R.string.lexicon_contextual_capacity_reached,
            formatArguments = listOf(error.maximum),
        )
        else -> LexiconErrorPresentation(R.string.lexicon_operation_failed)
    }
