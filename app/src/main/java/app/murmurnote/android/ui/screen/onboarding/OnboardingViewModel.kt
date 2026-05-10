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

    fun testBothConnections() = viewModelScope.launch {
        logger.i("Onboard", "test both connections requested")
        _uiState.update { it.copy(testing = true) }
        // 两次网络往返并发跑：用户在 onboarding 页等到的时间 = max(GLM, LLM)，不再是相加。
        val (g, o) = coroutineScope {
            val gd = async { glmAsrClient.testConnection() }
            val od = async { llmClient.testConnection() }
            gd.await() to od.await()
        }
        logger.i(
            "Onboard",
            "test result GLM=${g.isSuccess} LLM=${o.isSuccess} glmErr=${g.exceptionOrNull()?.message?.take(120) ?: "-"} llmErr=${o.exceptionOrNull()?.message?.take(120) ?: "-"}"
        )
        val (ok, msg) = when {
            g.isSuccess && o.isSuccess -> true to "✓ 两个连接都正常"
            g.isFailure -> false to "GLM 连接失败：${g.exceptionOrNull()?.message ?: ""}"
            else -> false to "LLM 连接失败：${o.exceptionOrNull()?.message ?: ""}"
        }
        _uiState.update { it.copy(testing = false, testSuccess = ok, testResult = msg) }
    }

    fun completeOnboarding() = viewModelScope.launch {
        logger.i("Onboard", "onboarding completed")
        appPreferences.setOnboardingCompleted(true)
    }
}
