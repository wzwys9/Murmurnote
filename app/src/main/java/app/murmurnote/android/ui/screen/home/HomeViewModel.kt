package app.murmurnote.android.ui.screen.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.audio.AudioImporter
import app.murmurnote.android.audio.RecordingController
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.PipelineStatusBus
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val recordingController: RecordingController,
    private val audioImporter: AudioImporter,
    private val statusBus: PipelineStatusBus,
    private val logger: Logger
) : ViewModel() {

    data class UiState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val elapsedMs: Long = 0,
        val amplitudeDb: Int = 0,
        val todayCount: Int = 0,
        val totalCount: Int = 0,
        val errorMessage: String? = null,
        val pipelineStage: PipelineStage = PipelineStage.Idle
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            recordingRepository.observeTotalCount().collect { total ->
                _uiState.update { it.copy(totalCount = total) }
            }
        }
        viewModelScope.launch {
            // "今日"边界要按本地时区算，UTC 偏 8h 会导致 CST 用户在凌晨~8 点之间数错。
            // 跨午夜还要重新计算边界——用一个轮询 flow 在每分钟和日期变化时刷新即可。
            dailyStartFlow().flatMapLatest { startOfDay ->
                recordingRepository.observeCountSince(startOfDay)
            }.collect { today ->
                _uiState.update { it.copy(todayCount = today) }
            }
        }
        // 实时反映 Pipeline 进度，让用户随时知道正在干嘛
        viewModelScope.launch {
            statusBus.stage.collect { st ->
                _uiState.update { it.copy(pipelineStage = st) }
            }
        }
    }

    /** 当前本地午夜的 epoch ms，每分钟检查一次；只在日期翻页时往下游 emit 新值，避免无谓刷新。 */
    private fun dailyStartFlow() = flow {
        var lastDate: LocalDate? = null
        while (true) {
            val today = LocalDate.now()
            if (today != lastDate) {
                lastDate = today
                emit(today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
            delay(60_000)
        }
    }

    fun startRecording() {
        if (recordingController.isRecording) return
        logger.i("Home", "startRecording requested")
        recordingController.start()
            .onSuccess {
                _uiState.update { it.copy(isRecording = true, isPaused = false, errorMessage = null, elapsedMs = 0) }
                startTicker()
            }
            .onFailure { e ->
                logger.e("Home", "startRecording failed", e)
                _uiState.update { it.copy(errorMessage = "录音启动失败：${e.message ?: e.javaClass.simpleName}") }
            }
    }

    fun stopRecording() {
        if (!recordingController.isRecording) return
        logger.i("Home", "stopRecording requested elapsed=${recordingController.elapsedMs()}ms")
        tickerJob?.cancel()
        recordingController.stopAndSubmit()
            .onSuccess { f ->
                logger.i("Home", "submitted to pipeline file=${f.absolutePath} size=${f.length()}")
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        elapsedMs = 0,
                        errorMessage = null
                    )
                }
            }
            .onFailure { e ->
                logger.e("Home", "stopRecording failed", e)
                _uiState.update { it.copy(isRecording = false, errorMessage = e.message ?: "停止失败") }
            }
    }

    fun togglePause() {
        if (!recordingController.isRecording) return
        if (recordingController.isPaused) recordingController.resume() else recordingController.pause()
        _uiState.update { it.copy(isPaused = recordingController.isPaused) }
    }

    fun cancelRecording() {
        logger.i("Home", "cancelRecording")
        tickerJob?.cancel()
        recordingController.cancel()
        _uiState.update { it.copy(isRecording = false, isPaused = false, elapsedMs = 0) }
    }

    fun reportPermissionDenied() {
        logger.w("Home", "RECORD_AUDIO permission denied by user")
        _uiState.update { it.copy(errorMessage = "需要录音权限才能开始录音。请在系统设置中授权。") }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            logger.i("Home", "importAudio uri=$uri")
            audioImporter.importAndProcess(uri)
                .onSuccess { f ->
                    _uiState.update { it.copy(errorMessage = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(errorMessage = "导入失败：${e.message ?: e.javaClass.simpleName}") }
                }
        }
    }

    fun dismissPipelineStatus() {
        statusBus.dismiss()
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (recordingController.isRecording) {
                _uiState.update {
                    it.copy(
                        elapsedMs = recordingController.elapsedMs(),
                        amplitudeDb = recordingController.amplitudeDb()
                    )
                }
                delay(100)
            }
        }
    }

    override fun onCleared() {
        tickerJob?.cancel()
    }
}
