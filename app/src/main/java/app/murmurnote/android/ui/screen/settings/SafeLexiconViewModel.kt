package app.murmurnote.android.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.repository.TranscriptRepository
import app.murmurnote.android.domain.correction.CorrectionRule
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SafeLexiconViewModel @Inject constructor(
    private val transcriptRepository: TranscriptRepository,
) : ViewModel() {

    data class UiState(
        val rules: List<CorrectionRule> = emptyList(),
        val isLoading: Boolean = true,
        val showAddDialog: Boolean = false,
        val observedDraft: String = "",
        val replacementDraft: String = "",
        val isSaving: Boolean = false,
        val updatingRuleIds: Set<String> = emptySet(),
        val pendingDeleteRule: CorrectionRule? = null,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            transcriptRepository.observeGlobalLexiconRules().collect { rules ->
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

    fun saveRule() {
        val snapshot = _uiState.value
        if (snapshot.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            runCatching {
                transcriptRepository.createGlobalLexiconRule(
                    observedText = snapshot.observedDraft,
                    replacementText = snapshot.replacementDraft,
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        observedDraft = "",
                        replacementDraft = "",
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
                transcriptRepository.setGlobalLexiconRuleEnabled(ruleId, enabled)
            }.onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.toUserMessage()) }
            }
            _uiState.update {
                it.copy(updatingRuleIds = it.updatingRuleIds - ruleId)
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
                transcriptRepository.deleteGlobalLexiconRule(rule.id)
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

    private fun Throwable.toUserMessage(): String =
        (this as? IllegalArgumentException)?.message
            ?.takeIf { it.isNotBlank() }
            ?: "操作失败，请重试"
}
