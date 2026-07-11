package app.murmurnote.android.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.glm.GlmAsrClient
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val llmClient: LlmClient,
    private val glmAsrClient: GlmAsrClient,
    private val logger: Logger
) : ViewModel() {

    data class UiState(
        val glmApiKey: String = "",
        val llmApiKey: String = "",
        val testResult: String? = null,
        val testSuccess: Boolean? = null,
        val testing: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.glmApiKey.collect { v -> _uiState.update { it.copy(glmApiKey = v) } }
        }
        viewModelScope.launch {
            appPreferences.llmApiKey.collect { v -> _uiState.update { it.copy(llmApiKey = v) } }
        }
    }

    fun updateGlmApiKey(k: String) = viewModelScope.launch {
        _uiState.update { it.copy(glmApiKey = k, testResult = null, testSuccess = null) }
        appPreferences.setGlmApiKey(k)
    }

    fun updateLlmApiKey(k: String) = viewModelScope.launch {
        _uiState.update { it.copy(llmApiKey = k, testResult = null, testSuccess = null) }
        appPreferences.setLlmApiKey(k)
    }

    fun testConfiguredConnections() = viewModelScope.launch {
        val testGlm = _uiState.value.glmApiKey.isNotBlank()
        val testLlm = _uiState.value.llmApiKey.isNotBlank()
        if (!testGlm && !testLlm) {
            _uiState.update {
                it.copy(
                    testResult = "云服务均未配置；可以直接继续使用本地转写。",
                    testSuccess = true
                )
            }
            return@launch
        }

        logger.i("Onboard", "test optional cloud connections requested glm=$testGlm llm=$testLlm")
        _uiState.update { it.copy(testing = true) }
        val (g, o) = coroutineScope {
            val gd = if (testGlm) async { glmAsrClient.testConnection() } else null
            val od = if (testLlm) async { llmClient.testConnection() } else null
            gd?.await() to od?.await()
        }
        val failures = buildList {
            if (g?.isFailure == true) add("GLM")
            if (o?.isFailure == true) add("LLM")
        }
        val testedNames = listOfNotNull("GLM".takeIf { testGlm }, "LLM".takeIf { testLlm })
        val ok = failures.isEmpty()
        val msg = if (ok) {
            "✓ ${testedNames.joinToString("、")} 连接正常"
        } else {
            "${failures.joinToString("、")} 连接失败；可稍后在设置中重试"
        }
        logger.i("Onboard", "optional cloud connection result success=$ok")
        _uiState.update { it.copy(testing = false, testSuccess = ok, testResult = msg) }
    }

    fun completeOnboarding(onComplete: () -> Unit) = viewModelScope.launch {
        appPreferences.completeOnboarding()
        logger.i("Onboard", "onboarding completed with local-first defaults persisted")
        onComplete()
    }
}
