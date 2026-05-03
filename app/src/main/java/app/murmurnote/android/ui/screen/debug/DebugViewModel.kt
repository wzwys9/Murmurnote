package app.murmurnote.android.ui.screen.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.local.entity.ApiLog
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.repository.ApiLogRepository
import app.murmurnote.android.util.LogExporter
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * runtime.log 单行的解析结果。level = I/W/E/D，未识别时归 I。
 * raw 是原始整行（含时间戳 + 标签 + 消息 + 可能的多行栈）。
 */
data class RuntimeLogLine(val level: String, val raw: String)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val apiLogRepo: ApiLogRepository,
    private val appPreferences: AppPreferences,
    private val logger: Logger,
    private val logExporter: LogExporter
) : ViewModel() {

    val apiLogs: StateFlow<List<ApiLog>> = apiLogRepo.observeRecent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt = _systemPrompt.asStateFlow()
    private val _userPrompt = MutableStateFlow("")
    val userPrompt = _userPrompt.asStateFlow()

    private val _forceFail = MutableStateFlow(false)
    val forceFail = _forceFail.asStateFlow()
    private val _simulateDelayMs = MutableStateFlow(0L)
    val simulateDelayMs = _simulateDelayMs.asStateFlow()

    private val _runtimeLogLines = MutableStateFlow<List<RuntimeLogLine>>(emptyList())
    val runtimeLogLines: StateFlow<List<RuntimeLogLine>> = _runtimeLogLines.asStateFlow()

    private val _runtimeLogLoading = MutableStateFlow(false)
    val runtimeLogLoading = _runtimeLogLoading.asStateFlow()

    private val _shareInProgress = MutableStateFlow(false)
    val shareInProgress = _shareInProgress.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.systemPromptOverride.collect { v -> _systemPrompt.value = v.orEmpty() }
        }
        viewModelScope.launch {
            appPreferences.userPromptOverride.collect { v -> _userPrompt.value = v.orEmpty() }
        }
        viewModelScope.launch {
            appPreferences.debugForceNetworkFail.collect { v -> _forceFail.value = v }
        }
        viewModelScope.launch {
            appPreferences.debugSimulateDelayMs.collect { v -> _simulateDelayMs.value = v }
        }
    }

    fun clearApiLogs() = viewModelScope.launch { apiLogRepo.clear() }
    fun setSystemPrompt(v: String) {
        _systemPrompt.value = v
        viewModelScope.launch { appPreferences.setSystemPromptOverride(v.takeIf { it.isNotBlank() }) }
    }
    fun setUserPrompt(v: String) {
        _userPrompt.value = v
        viewModelScope.launch { appPreferences.setUserPromptOverride(v.takeIf { it.isNotBlank() }) }
    }
    fun resetPrompts() = viewModelScope.launch {
        appPreferences.setSystemPromptOverride(null)
        appPreferences.setUserPromptOverride(null)
        _systemPrompt.value = ""
        _userPrompt.value = ""
    }
    fun toggleForceFail() = viewModelScope.launch {
        appPreferences.setDebugForceNetworkFail(!_forceFail.value)
    }
    fun setSimulateDelay(ms: Long) = viewModelScope.launch {
        appPreferences.setDebugSimulateDelayMs(ms)
    }

    /**
     * 拉取 runtime.log 末尾 [maxLines] 行。Logger 有 2MB 轮转兜底，
     * 单次 readLines() 最多读约 2MB，对调试机够用、内存友好。
     */
    fun refreshRuntimeLog(maxLines: Int = 500) = viewModelScope.launch(Dispatchers.IO) {
        _runtimeLogLoading.value = true
        try {
            val file = logger.logFile()
            val lines = if (!file.exists()) emptyList() else file.readLines()
            val tail = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            _runtimeLogLines.value = tail.map { line ->
                // 行格式：MM-dd HH:mm:ss.SSS L [scope] message
                // 多行栈帧（"\tat ..."）继承上一行的 level，但因为我们按 raw 行渲染，
                // 这里只看每行第一个字符可识别的 level；识别不到归 I 即可。
                val level = LEVEL_RE.find(line)?.groupValues?.getOrNull(1) ?: "I"
                RuntimeLogLine(level, line)
            }
        } finally {
            _runtimeLogLoading.value = false
        }
    }

    fun clearRuntimeLog() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { logger.logFile().writeText("") }
        _runtimeLogLines.value = emptyList()
    }

    fun shareLogBundle(activityContext: Context) = viewModelScope.launch {
        if (_shareInProgress.value) return@launch
        _shareInProgress.value = true
        try {
            val r = logExporter.exportAndShare(activityContext)
            if (r.isFailure) logger.e("Debug", "share log failed", r.exceptionOrNull())
        } finally {
            _shareInProgress.value = false
        }
    }

    private companion object {
        private val LEVEL_RE = Regex("""^\d{2}-\d{2} \S+ ([IWED]) """)
    }
}
