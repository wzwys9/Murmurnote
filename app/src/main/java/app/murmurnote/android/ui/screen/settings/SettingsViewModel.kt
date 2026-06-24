package app.murmurnote.android.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.LocalAsrModelSpec
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.data.asr.AsrModelUrls
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.llm.LlmProvider
import app.murmurnote.android.data.remote.glm.GlmAsrClient
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.service.AsrModelDownloadService
import app.murmurnote.android.util.LogExporter
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TestStatus {
    data object Idle : TestStatus()
    data object Testing : TestStatus()
    data object Success : TestStatus()
    data class Failed(val message: String) : TestStatus()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val llmClient: LlmClient,
    private val glmAsrClient: GlmAsrClient,
    private val asrModelManager: AsrModelManager,
    private val localAsrEngine: app.murmurnote.android.data.asr.LocalAsrEngine,
    private val logExporter: LogExporter,
    private val logger: Logger
) : ViewModel() {

    data class UiState(
        val glmApiKey: String = "",
        val llmApiKey: String = "",
        val llmProvider: String = LlmProvider.DEEPSEEK.name,
        val glmBaseUrl: String = "",
        val llmBaseUrl: String = "",
        val llmModel: String = "",
        val reasoningEffort: String = "high",
        val availableLlmModels: List<String> = emptyList(),
        val isLoadingModels: Boolean = false,
        val modelLoadError: String? = null,
        val glmTestStatus: TestStatus = TestStatus.Idle,
        val llmTestStatus: TestStatus = TestStatus.Idle,
        val exportLogResult: String? = null,
        val exportingLog: Boolean = false,
        // ASR 引擎切换
        val asrEngineType: String = AsrEngineType.CLOUD_GLM.name,
        val asrMirrorIndex: Int = 0,
        val asrMirrorOptions: List<String> = listOf("GitHub 直连", "ghproxy 镜像", "gh-proxy 镜像"),
        val asrLocalModelId: String = AsrModelUrls.DEFAULT_MODEL_ID,
        val asrLocalModels: List<LocalAsrModelSpec> = AsrModelUrls.MODELS,
        val asrModelStatus: AsrModelManager.ModelStatus = AsrModelManager.ModelStatus.NotDownloaded,
        val asrModelUpdateCheck: AsrModelManager.ModelUpdateCheck? = null,
        val asrModelUpdateChecking: Boolean = false,
        val showAsrDownloadConfirm: Boolean = false,
        val showAsrHashMismatchConfirm: Boolean = false,
        // sherpa-onnx Kotlin/JNI 类是否能加载（即 app/libs/ 下的 AAR 是否打进了 APK）。
        // 跟模型文件状态正交：模型文件可以在线下，但 AAR 必须编译期就绪。
        val asrNativeLibReady: Boolean = false,
        val asrLocalConcurrency: Int = 1,
        val realtimePerformanceMode: String = "BALANCED",
        val lowBatteryProtection: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch { appPreferences.glmApiKey.collect { v -> _uiState.update { it.copy(glmApiKey = v) } } }
        viewModelScope.launch { appPreferences.llmApiKey.collect { v -> _uiState.update { it.copy(llmApiKey = v) } } }
        viewModelScope.launch { appPreferences.llmProvider.collect { v -> _uiState.update { it.copy(llmProvider = v) } } }
        viewModelScope.launch { appPreferences.glmBaseUrl.collect { v -> _uiState.update { it.copy(glmBaseUrl = v) } } }
        viewModelScope.launch { appPreferences.llmBaseUrl.collect { v -> _uiState.update { it.copy(llmBaseUrl = v) } } }
        viewModelScope.launch { appPreferences.llmModel.collect { v -> _uiState.update { it.copy(llmModel = v) } } }
        viewModelScope.launch { appPreferences.reasoningEffort.collect { v -> _uiState.update { it.copy(reasoningEffort = v) } } }
        viewModelScope.launch { appPreferences.asrEngineType.collect { v -> _uiState.update { it.copy(asrEngineType = v) } } }
        viewModelScope.launch {
            appPreferences.asrLocalModelId.collect { v ->
                _uiState.update {
                    it.copy(
                        asrLocalModelId = v,
                        asrModelUpdateCheck = null,
                        asrModelUpdateChecking = false
                    )
                }
            }
        }
        viewModelScope.launch { appPreferences.asrDownloadMirrorIndex.collect { v -> _uiState.update { it.copy(asrMirrorIndex = v) } } }
        viewModelScope.launch {
            asrModelManager.status.collect { v ->
                _uiState.update {
                    it.copy(
                        asrModelStatus = v,
                        asrModelUpdateCheck = if (v is AsrModelManager.ModelStatus.Ready) it.asrModelUpdateCheck else null,
                        asrModelUpdateChecking = if (v is AsrModelManager.ModelStatus.Ready) it.asrModelUpdateChecking else false,
                        showAsrHashMismatchConfirm = v is AsrModelManager.ModelStatus.HashMismatch
                    )
                }
            }
        }
        viewModelScope.launch { appPreferences.asrLocalConcurrency.collect { v -> _uiState.update { it.copy(asrLocalConcurrency = v) } } }
        viewModelScope.launch { appPreferences.realtimePerformanceMode.collect { v -> _uiState.update { it.copy(realtimePerformanceMode = v) } } }
        viewModelScope.launch { appPreferences.lowBatteryProtection.collect { v -> _uiState.update { it.copy(lowBatteryProtection = v) } } }
        // 进设置页主动算一次"模型在不在"，触发状态广播；同时探测一次原生库是否在 classpath。
        viewModelScope.launch {
            runCatching { asrModelManager.installBundledModelIfNeeded() }
                .onFailure { e -> logger.w("Settings", "bundled ASR model install skipped/failed: ${e.message}") }
            asrModelManager.refreshStatus()
            _uiState.update { it.copy(asrNativeLibReady = localAsrEngine.nativeLibReady()) }
        }
    }

    fun updateGlmApiKey(key: String) = viewModelScope.launch {
        appPreferences.setGlmApiKey(key)
        _uiState.update { it.copy(glmTestStatus = TestStatus.Idle) }
    }
    fun updateLlmApiKey(key: String) = viewModelScope.launch {
        appPreferences.setLlmApiKey(key)
        _uiState.update { it.copy(llmTestStatus = TestStatus.Idle) }
    }
    fun updateLlmProvider(providerName: String) = viewModelScope.launch {
        val provider = LlmProvider.parse(providerName)
        appPreferences.setLlmProvider(provider)
        _uiState.update {
            it.copy(
                llmProvider = provider.name,
                llmTestStatus = TestStatus.Idle,
                availableLlmModels = emptyList(),
                modelLoadError = null
            )
        }
        refreshLlmModels()
    }
    fun updateLlmModel(m: String) = viewModelScope.launch { appPreferences.setLlmModel(m) }
    fun updateReasoningEffort(e: String) = viewModelScope.launch { appPreferences.setReasoningEffort(e) }
    fun updateGlmBaseUrl(u: String) = viewModelScope.launch { appPreferences.setGlmBaseUrl(u) }
    fun updateLlmBaseUrl(u: String) = viewModelScope.launch { appPreferences.setLlmBaseUrl(u) }

    fun refreshLlmModels() = viewModelScope.launch {
        _uiState.update { it.copy(isLoadingModels = true, modelLoadError = null) }
        llmClient.fetchAvailableModels().fold(
            onSuccess = { models ->
                val selected = _uiState.value.llmModel
                if (selected.isBlank() && models.isNotEmpty()) {
                    appPreferences.setLlmModel(models.first())
                }
                _uiState.update { it.copy(availableLlmModels = models, isLoadingModels = false) }
            },
            onFailure = { e -> _uiState.update { it.copy(isLoadingModels = false, modelLoadError = e.message ?: "未知错误") } }
        )
    }

    fun testGlmConnection() = viewModelScope.launch {
        logger.i("Settings", "test GLM connection requested")
        _uiState.update { it.copy(glmTestStatus = TestStatus.Testing) }
        val r = glmAsrClient.testConnection()
        r.fold(
            onSuccess = { logger.i("Settings", "GLM test → success") },
            onFailure = { e -> logger.e("Settings", "GLM test → FAILED: ${describe(e)}", e) }
        )
        _uiState.update {
            it.copy(glmTestStatus = r.fold({ TestStatus.Success }, { TestStatus.Failed(describe(it)) }))
        }
    }

    fun testLlmConnection() = viewModelScope.launch {
        logger.i("Settings", "test LLM connection requested")
        _uiState.update { it.copy(llmTestStatus = TestStatus.Testing) }
        val r = llmClient.testConnection()
        r.fold(
            onSuccess = { logger.i("Settings", "LLM test → success") },
            onFailure = { e -> logger.e("Settings", "LLM test → FAILED: ${describe(e)}", e) }
        )
        _uiState.update {
            it.copy(llmTestStatus = r.fold({ TestStatus.Success }, { TestStatus.Failed(describe(it)) }))
        }
    }

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    fun exportLog() = viewModelScope.launch {
        _uiState.update { it.copy(exportingLog = true, exportLogResult = null) }
        logger.i("Settings", "exportLog requested")
        val r = logExporter.exportToDownloads()
        _uiState.update {
            it.copy(
                exportingLog = false,
                exportLogResult = r.fold(
                    onSuccess = { path -> "✓ 已导出到 $path" },
                    onFailure = { e -> "✗ 导出失败：${e.message}" }
                )
            )
        }
    }

    /**
     * 通过 ACTION_SEND 拉起系统分享面板。activityContext 由 Composable 传 LocalContext.current。
     * 失败时把错误回写到 exportLogResult 复用同一个 UI 槽位，免得再加一个 state 字段。
     */
    fun shareLog(activityContext: Context) = viewModelScope.launch {
        _uiState.update { it.copy(exportingLog = true, exportLogResult = null) }
        logger.i("Settings", "shareLog requested")
        val r = logExporter.exportAndShare(activityContext)
        _uiState.update {
            it.copy(
                exportingLog = false,
                exportLogResult = r.fold(
                    onSuccess = { null },                      // 成功就让分享面板替我们说话，不刷字
                    onFailure = { e -> "✗ 分享失败：${e.message}" }
                )
            )
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportLogResult = null) }
    }

    // -------------------- ASR 引擎切换 / 模型管理 --------------------

    fun setAsrEngineType(t: String) = viewModelScope.launch {
        appPreferences.setAsrEngineType(t)
        logger.i("Settings", "asr engine switched → $t")
    }

    fun setAsrMirrorIndex(i: Int) = viewModelScope.launch {
        appPreferences.setAsrDownloadMirrorIndex(i.coerceIn(0, AsrModelUrls.MIRROR_PREFIXES.lastIndex))
    }

    fun setAsrLocalModel(id: String) = viewModelScope.launch {
        localAsrEngine.release()
        asrModelManager.selectModel(id)
        runCatching { asrModelManager.installBundledModelIfNeeded() }
            .onFailure { e -> logger.w("Settings", "bundled ASR model install skipped/failed: ${e.message}") }
        asrModelManager.refreshStatus()
        _uiState.update { it.copy(asrLocalModelId = AsrModelUrls.modelById(id).id) }
        logger.i("Settings", "local asr model switched → ${AsrModelUrls.modelById(id).id}")
    }

    fun requestAsrDownloadConfirm() {
        _uiState.update { it.copy(showAsrDownloadConfirm = true) }
    }

    fun dismissAsrDownloadConfirm() {
        _uiState.update { it.copy(showAsrDownloadConfirm = false) }
    }

    fun requestInstallHashMismatchModel() {
        _uiState.update { it.copy(showAsrHashMismatchConfirm = true) }
    }

    fun dismissInstallHashMismatchModel() {
        _uiState.update { it.copy(showAsrHashMismatchConfirm = false) }
    }

    fun startAsrDownload(activityContext: Context) {
        _uiState.update { it.copy(showAsrDownloadConfirm = false, asrModelUpdateCheck = null) }
        logger.i("Settings", "asr download start requested")
        AsrModelDownloadService.start(activityContext)
    }

    fun installHashMismatchModel(activityContext: Context) {
        _uiState.update { it.copy(showAsrHashMismatchConfirm = false) }
        logger.w("Settings", "user confirmed installing ASR model with SHA256 mismatch")
        AsrModelDownloadService.installUnverified(activityContext)
    }

    fun setAsrLocalConcurrency(v: Int) = viewModelScope.launch {
        appPreferences.setAsrLocalConcurrency(v)
    }

    fun setRealtimePerformanceMode(v: String) = viewModelScope.launch {
        appPreferences.setRealtimePerformanceMode(v)
    }

    fun setLowBatteryProtection(v: Boolean) = viewModelScope.launch {
        appPreferences.setLowBatteryProtection(v)
    }

    fun cancelAsrDownload(activityContext: Context) {
        logger.i("Settings", "asr download cancel requested")
        AsrModelDownloadService.cancel(activityContext)
    }

    fun deleteAsrModel() = viewModelScope.launch {
        logger.i("Settings", "asr model delete requested")
        _uiState.update { it.copy(asrModelUpdateCheck = null, asrModelUpdateChecking = false) }
        asrModelManager.delete()
    }

    fun checkAsrModelUpdate() = viewModelScope.launch {
        if (_uiState.value.asrModelUpdateChecking) return@launch
        logger.i("Settings", "asr model update check requested")
        _uiState.update { it.copy(asrModelUpdateChecking = true, asrModelUpdateCheck = null) }
        val result = runCatching { asrModelManager.checkForUpdate() }
            .getOrElse { e ->
                logger.w("Settings", "asr model update check failed: ${e.message}")
                AsrModelManager.ModelUpdateCheck.UnableToCheck(e.message ?: e.javaClass.simpleName)
            }
        _uiState.update { it.copy(asrModelUpdateChecking = false, asrModelUpdateCheck = result) }
    }
}
