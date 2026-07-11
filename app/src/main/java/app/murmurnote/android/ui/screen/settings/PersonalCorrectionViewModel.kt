package app.murmurnote.android.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.repository.PersonalCorrectionRepository
import app.murmurnote.android.domain.correction.PersonalCorrectionProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PersonalCorrectionViewModel @Inject constructor(
    private val repository: PersonalCorrectionRepository,
) : ViewModel() {
    data class UiState(
        val profiles: List<PersonalCorrectionProfile> = emptyList(),
        val isLoading: Boolean = true,
        val updatingRuleIds: Set<String> = emptySet(),
        val pendingDelete: PersonalCorrectionProfile? = null,
        val confirmClearAll: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeProfiles().collect { profiles ->
                _uiState.update { it.copy(profiles = profiles, isLoading = false) }
            }
        }
    }

    fun setEnabled(profile: PersonalCorrectionProfile, enabled: Boolean) {
        if (profile.ruleId in _uiState.value.updatingRuleIds) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updatingRuleIds = it.updatingRuleIds + profile.ruleId,
                    errorMessage = null,
                )
            }
            runCatching { repository.setProfileEnabled(profile.ruleId, enabled) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "操作失败，请重试")
                    }
                }
            _uiState.update {
                it.copy(updatingRuleIds = it.updatingRuleIds - profile.ruleId)
            }
        }
    }

    fun requestDelete(profile: PersonalCorrectionProfile) {
        _uiState.update { it.copy(pendingDelete = profile, errorMessage = null) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val profile = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingDelete = null,
                    updatingRuleIds = it.updatingRuleIds + profile.ruleId,
                    errorMessage = null,
                )
            }
            runCatching { repository.deleteProfile(profile.ruleId) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "删除失败，请重试")
                    }
                }
            _uiState.update {
                it.copy(updatingRuleIds = it.updatingRuleIds - profile.ruleId)
            }
        }
    }

    fun requestClearAll() {
        _uiState.update { it.copy(confirmClearAll = true, errorMessage = null) }
    }

    fun dismissClearAll() {
        _uiState.update { it.copy(confirmClearAll = false) }
    }

    fun confirmClearAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(confirmClearAll = false, errorMessage = null) }
            runCatching { repository.clearProfiles() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "清空失败，请重试")
                    }
                }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
