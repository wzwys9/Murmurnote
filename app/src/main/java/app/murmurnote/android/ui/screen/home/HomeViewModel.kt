package app.murmurnote.android.ui.screen.home

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.audio.AudioImporter
import app.murmurnote.android.audio.AudioRecorder
import app.murmurnote.android.audio.LiveVadWorkerState
import app.murmurnote.android.audio.NeuralVadSegmentPlanner
import app.murmurnote.android.audio.RecordingController
import app.murmurnote.android.data.asr.AsrEngineProvider
import app.murmurnote.android.data.asr.LocalAsrEngine
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.PipelineStatusBus
import app.murmurnote.android.domain.pipeline.ProcessingQueueEntry
import app.murmurnote.android.domain.pipeline.ProcessingQueueTracker
import app.murmurnote.android.domain.pipeline.ProcessingStartupRecovery
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val recordingController: RecordingController,
    private val audioImporter: AudioImporter,
    private val asrEngineProvider: AsrEngineProvider,
    private val statusBus: PipelineStatusBus,
    private val queueTracker: ProcessingQueueTracker,
    private val processingStartupRecovery: ProcessingStartupRecovery,
    private val logger: Logger
) : ViewModel() {

    enum class LiveTranscriptStatus { WAITING, TRANSCRIBING, TRANSCRIBED, FAILED }

    data class LiveTranscriptSegment(
        val sequence: Int,
        val startMs: Long,
        val endMs: Long,
        val status: LiveTranscriptStatus,
        val text: String = "",
        val errorMessage: String? = null,
        val progress: Float? = null
    )

    data class UiState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val elapsedMs: Long = 0,
        val amplitudeDb: Int = 0,
        val todayCount: Int = 0,
        val totalCount: Int = 0,
        val errorMessage: String? = null,
        val pipelineStage: PipelineStage = PipelineStage.Idle,
        val processingQueue: List<ProcessingQueueEntry> = emptyList(),
        val liveTranscriptionActive: Boolean = false,
        val liveTranscriptionMessage: String? = null,
        val liveTranscriptSegments: List<LiveTranscriptSegment> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private var recordingStartJob: Job? = null
    private var recordingFinalizationJob: Job? = null
    private var liveTranscriptionJob: Job? = null
    private val liveRetryQueue = ConcurrentLinkedQueue<Int>()
    private var livePreviewGeneration = 0L
    private var activeRecordingId: String? = null

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
        viewModelScope.launch {
            queueTracker.entries.collect { entries ->
                _uiState.update { it.copy(processingQueue = entries) }
            }
        }
        restoreActiveRecordingSession()
    }

    private fun restoreActiveRecordingSession() {
        val session = recordingController.activeSession() ?: return
        activeRecordingId = session.id
        _uiState.update { current -> restoreRecordingUiState(current, session) }
        startTicker()
        logger.i(
            "Home",
            "restored active background recording",
            fields = mapOf(
                "recordingId" to session.id,
                "elapsedMs" to session.elapsedMs,
                "paused" to session.isPaused,
            ),
        )
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
        if (recordingController.isRecording ||
            recordingStartJob?.isActive == true ||
            recordingFinalizationJob?.isActive == true
        ) return
        logger.i("Home", "startRecording requested")
        recordingController.prepareForRecording().getOrElse { error ->
            _uiState.update {
                it.copy(
                    errorMessage =
                        "录音启动失败：${error.message ?: error.javaClass.simpleName}"
                )
            }
            return
        }
        var startupCommitted = false
        var startupRecordingId: String? = null
        val job = viewModelScope.launch {
            try {
                processingStartupRecovery.awaitCompletion()
                val active = withContext(Dispatchers.IO) { recordingController.start() }
                    .getOrElse { error ->
                        logger.e("Home", "startRecording failed", error)
                        _uiState.update {
                            it.copy(
                                errorMessage =
                                    "录音启动失败：${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                        return@launch
                    }
                startupRecordingId = active.id
                try {
                    recordingRepository.insert(
                        Recording(
                            id = active.id,
                            title = "录音中",
                            originalFilePath = active.file.absolutePath,
                            durationMs = 0L,
                            createdAt = active.createdAt,
                            source = RecordingSource.RECORDED,
                            processingStatus = ProcessingStatus.RECORDING,
                            audioExpiresAt = active.createdAt + 30L * 24 * 3600 * 1000
                        )
                    )
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    logger.e("Home", "failed to create recording draft", failure)
                    _uiState.update {
                        it.copy(
                            errorMessage =
                                "创建录音记录失败：${failure.message ?: failure.javaClass.simpleName}"
                        )
                    }
                    return@launch
                }
                activeRecordingId = active.id
                _uiState.update {
                    it.copy(
                        isRecording = true,
                        isPaused = false,
                        errorMessage = null,
                        elapsedMs = 0,
                        amplitudeDb = 0,
                        liveTranscriptionActive = true,
                        liveTranscriptionMessage = "正在启动实时 Silero VAD…",
                        liveTranscriptSegments = emptyList()
                    )
                }
                startTicker()
                startLiveTranscription()
                startupCommitted = true
            } finally {
                if (!startupCommitted) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { recordingController.cancel() }
                            .onFailure { failure ->
                                logger.e("Home", "failed to roll back recording startup", failure)
                            }
                        startupRecordingId?.let { recordingId ->
                            runCatching { recordingRepository.delete(recordingId) }
                                .onFailure { failure ->
                                    logger.e(
                                        "Home",
                                        "failed to remove rolled-back recording draft",
                                        failure
                                    )
                                }
                        }
                    }
                    if (activeRecordingId == startupRecordingId) {
                        activeRecordingId = null
                        _uiState.update {
                            it.copy(
                                isRecording = false,
                                isPaused = false,
                                elapsedMs = 0,
                                amplitudeDb = 0,
                                liveTranscriptionActive = false,
                                liveTranscriptionMessage = null,
                                liveTranscriptSegments = emptyList()
                            )
                        }
                    }
                }
            }
        }
        recordingStartJob = job
        job.invokeOnCompletion {
            if (!recordingController.isRecording) {
                recordingController.abortPreparedRecording()
            }
            if (recordingStartJob === job) recordingStartJob = null
        }
    }

    fun stopRecording() {
        if (!recordingController.isRecording || recordingFinalizationJob?.isActive == true) return
        logger.i("Home", "stopRecording requested elapsed=${recordingController.elapsedMs()}ms")
        tickerJob?.cancel()
        stopLiveTranscription()
        _uiState.update {
            it.copy(liveTranscriptionActive = false, liveTranscriptionMessage = "正在保存录音…")
        }
        val recordingId = activeRecordingId
        val job = viewModelScope.launch {
            withContext(Dispatchers.IO) { recordingController.stopAndSubmit() }
                .onSuccess { file ->
                logger.i("Home", "submitted to pipeline size=${file.length()}")
                activeRecordingId = null
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        elapsedMs = 0,
                        amplitudeDb = 0,
                        errorMessage = null,
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = null
                    )
                }
            }.onFailure { error ->
                logger.e("Home", "stopRecording failed", error)
                if (recordingId != null) {
                    recordingRepository.markProcessingFailedIfInProgress(
                        recordingId,
                        "录音已保存，但处理服务启动失败，可手动重试"
                    )
                }
                activeRecordingId = null
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        amplitudeDb = 0,
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = null,
                        errorMessage = "录音已保存，但未能开始处理，请稍后手动重试。"
                    )
                }
            }
        }
        recordingFinalizationJob = job
        job.invokeOnCompletion {
            if (recordingFinalizationJob === job) recordingFinalizationJob = null
        }
    }

    fun togglePause() {
        if (!recordingController.isRecording) return
        if (recordingController.isPaused) recordingController.resume() else recordingController.pause()
        _uiState.update { it.copy(isPaused = recordingController.isPaused) }
    }

    fun cancelRecording() {
        if (recordingFinalizationJob?.isActive == true) return
        logger.i("Home", "cancelRecording")
        tickerJob?.cancel()
        stopLiveTranscription()
        val recordingId = activeRecordingId
        val job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { recordingController.cancel() }
                if (recordingId != null) recordingRepository.delete(recordingId)
                activeRecordingId = null
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        elapsedMs = 0,
                        amplitudeDb = 0,
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = null,
                        liveTranscriptSegments = emptyList()
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                logger.e("Home", "cancelRecording failed", failure)
                _uiState.update {
                    it.copy(errorMessage = "取消失败：${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
        recordingFinalizationJob = job
        job.invokeOnCompletion {
            if (recordingFinalizationJob === job) recordingFinalizationJob = null
        }
    }

    fun reportPermissionDenied() {
        logger.w("Home", "RECORD_AUDIO permission denied by user")
        _uiState.update { it.copy(errorMessage = "需要录音权限才能开始录音。请在系统设置中授权。") }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            logger.i("Home", "importAudio requested")
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

    fun cancelCurrentProcessing(context: Context) {
        logger.w("Home", "cancelCurrentProcessing requested")
        ContextCompat.startForegroundService(context, TranscriptionService.cancelCurrentIntent(context))
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun retryLiveSegment(sequence: Int) {
        val segment = recordingController.recordedSegments()
            .firstOrNull { it.sequence == sequence }
        if (segment == null || !recordingController.isRecording) {
            _uiState.update {
                it.copy(liveTranscriptionMessage = "该预览片段已不可用；停止后会完整重新识别。")
            }
            return
        }
        liveRetryQueue.offer(sequence)
        upsertLiveSegment(
            generation = livePreviewGeneration,
            LiveTranscriptSegment(
                sequence = segment.sequence,
                startMs = segment.startMs,
                endMs = segment.endMs,
                status = LiveTranscriptStatus.WAITING
            )
        )
        _uiState.update { it.copy(liveTranscriptionMessage = "已安排重试第 ${sequence + 1} 段…") }
    }

    private fun startLiveTranscription() {
        stopLiveTranscription()
        val generation = livePreviewGeneration
        liveRetryQueue.clear()
        val job = viewModelScope.launch(Dispatchers.IO) {
            val attempt = when (
                val selection = asrEngineProvider.snapshotAttempt(
                    vadPresetVersion = NeuralVadSegmentPlanner.PRESET.version,
                    locale = Locale.getDefault()
                )
            ) {
                is AsrEngineProvider.AttemptSelection.NotReady -> {
                    updateLiveUi(generation) {
                        it.copy(
                            liveTranscriptionActive = false,
                            liveTranscriptionMessage =
                                "实时预览不可用：${selection.reason}；停止后仍可重试完整处理。"
                        )
                    }
                    return@launch
                }

                is AsrEngineProvider.AttemptSelection.Active -> selection
            }
            val localConfig = attempt.localConfig
            val localEngine = attempt.engine as? LocalAsrEngine
            if (localConfig == null || localEngine == null) {
                attempt.engine.release()
                updateLiveUi(generation) {
                    it.copy(
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage =
                            "录音中不会把音频发送到云端；停止后将按当前云端设置完整转写。"
                    )
                }
                return@launch
            }

            val processedSequences = mutableSetOf<Int>()
            try {
                while (isActive && isLivePreviewCurrent(generation)) {
                    val recorded = recordingController.recordedSegments().sortedBy { it.sequence }
                    val bySequence = recorded.associateBy { it.sequence }
                    processedSequences.retainAll(bySequence.keys)
                    val retry = liveRetryQueue.poll()?.let(bySequence::get)
                    val next = retry ?: recorded.firstOrNull { it.sequence !in processedSequences }
                    if (next == null) {
                        updateLiveVadStatus(generation, recorded.size)
                        delay(LIVE_PREVIEW_POLL_MS)
                        continue
                    }

                    processedSequences += next.sequence
                    transcribeLiveSegment(generation, next, localEngine, localConfig)
                }
            } finally {
                attempt.engine.release()
            }
        }
        liveTranscriptionJob = job
        job.invokeOnCompletion {
            if (liveTranscriptionJob === job) liveTranscriptionJob = null
        }
    }

    private suspend fun transcribeLiveSegment(
        generation: Long,
        segment: AudioRecorder.RecordedSegment,
        engine: LocalAsrEngine,
        config: app.murmurnote.android.data.asr.LocalAsrSessionConfig,
        retryAttempt: Int = 0
    ) {
        if (!isLivePreviewCurrent(generation)) return
        upsertLiveSegment(
            generation = generation,
            LiveTranscriptSegment(
                sequence = segment.sequence,
                startMs = segment.startMs,
                endMs = segment.endMs,
                status = LiveTranscriptStatus.TRANSCRIBING,
                progress = 0f
            )
        )
        updateLiveUi(generation) {
            it.copy(
                liveTranscriptionActive = true,
                liveTranscriptionMessage = "正在本地预览第 ${segment.sequence + 1} 段…"
            )
        }

        engine.transcribe(segment.file, config) { progress ->
            upsertLiveSegment(
                generation = generation,
                LiveTranscriptSegment(
                    sequence = segment.sequence,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    status = LiveTranscriptStatus.TRANSCRIBING,
                    progress = progress
                )
            )
        }.fold(
            onSuccess = { result ->
                if (!isLivePreviewCurrent(generation)) return@fold
                upsertLiveSegment(
                    generation = generation,
                    LiveTranscriptSegment(
                        sequence = segment.sequence,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        status = LiveTranscriptStatus.TRANSCRIBED,
                        text = result.text,
                        progress = 1f
                    )
                )
                recordingController.discardRecordedSegment(segment.sequence)
                updateLiveUi(generation) { state ->
                    state.copy(
                        liveTranscriptionActive = true,
                        liveTranscriptionMessage =
                            "已本地预览 ${state.liveTranscriptSegments.count { it.status == LiveTranscriptStatus.TRANSCRIBED }} 段"
                    )
                }
            },
            onFailure = { failure ->
                if (!isLivePreviewCurrent(generation)) return@fold
                if (retryAttempt < LIVE_SEGMENT_AUTO_RETRIES) {
                    updateLiveUi(generation) {
                        it.copy(liveTranscriptionMessage = "第 ${segment.sequence + 1} 段失败，正在重试…")
                    }
                    delay(LIVE_SEGMENT_RETRY_DELAY_MS)
                    transcribeLiveSegment(
                        generation,
                        segment,
                        engine,
                        config,
                        retryAttempt + 1
                    )
                    return
                }
                upsertLiveSegment(
                    generation = generation,
                    LiveTranscriptSegment(
                        sequence = segment.sequence,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        status = LiveTranscriptStatus.FAILED,
                        errorMessage = failure.message?.takeIf(String::isNotBlank)
                            ?: failure.javaClass.simpleName
                    )
                )
                updateLiveUi(generation) {
                    it.copy(
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage =
                            "第 ${segment.sequence + 1} 段预览失败；主录音未受影响。"
                    )
                }
            }
        )
    }

    private fun updateLiveVadStatus(generation: Long, segmentCount: Int) {
        val snapshot = recordingController.liveVadSnapshot()
        val (active, message) = when (snapshot.state) {
            LiveVadWorkerState.NEW,
            LiveVadWorkerState.STARTING -> true to "正在启动实时 Silero VAD…"

            LiveVadWorkerState.RUNNING -> true to if (segmentCount == 0) {
                "Silero VAD 正在本地监听语音边界…"
            } else {
                "已生成 $segmentCount 个本地预览片段"
            }

            LiveVadWorkerState.FINISHING -> true to "正在完成最后一个本地预览片段…"
            LiveVadWorkerState.STOPPED -> false to "实时预览已结束；完整录音将重新识别。"
            LiveVadWorkerState.DISABLED_BACKPRESSURE -> false to
                "设备暂时跟不上实时 VAD，预览已停用；完整 WAV 未丢失，停止后会重新处理。"
            LiveVadWorkerState.FAILED_NEURAL_VAD -> false to
                "实时 Silero VAD 失败（${snapshot.failureType ?: "未知错误"}）；停止后会再次运行完整神经 VAD。"
            LiveVadWorkerState.ABORTED -> false to "实时预览已取消；完整录音不受影响。"
        }
        updateLiveUi(generation) {
            it.copy(liveTranscriptionActive = active, liveTranscriptionMessage = message)
        }
    }

    private fun upsertLiveSegment(generation: Long, segment: LiveTranscriptSegment) {
        updateLiveUi(generation) { state ->
            state.copy(
                liveTranscriptSegments = (
                    state.liveTranscriptSegments.filterNot { it.sequence == segment.sequence } + segment
                    ).sortedBy { it.sequence }.takeLast(MAX_VISIBLE_LIVE_SEGMENTS)
            )
        }
    }

    private inline fun updateLiveUi(generation: Long, update: (UiState) -> UiState) {
        if (generation == livePreviewGeneration) _uiState.update(update)
    }

    private fun isLivePreviewCurrent(generation: Long): Boolean =
        generation == livePreviewGeneration && recordingController.isRecording

    private fun stopLiveTranscription() {
        livePreviewGeneration++
        liveTranscriptionJob?.cancel()
        liveTranscriptionJob = null
        liveRetryQueue.clear()
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
        stopLiveTranscription()
    }

    private companion object {
        const val LIVE_PREVIEW_POLL_MS = 200L
        const val LIVE_SEGMENT_AUTO_RETRIES = 1
        const val LIVE_SEGMENT_RETRY_DELAY_MS = 500L
        const val MAX_VISIBLE_LIVE_SEGMENTS = 50
    }
}

internal fun restoreRecordingUiState(
    current: HomeViewModel.UiState,
    session: RecordingController.ActiveSession,
): HomeViewModel.UiState = current.copy(
    isRecording = true,
    isPaused = session.isPaused,
    elapsedMs = session.elapsedMs,
    amplitudeDb = session.amplitudeDb,
    errorMessage = null,
    liveTranscriptionActive = false,
    liveTranscriptionMessage = "录音正在后台继续；停止后会完整重新识别。",
    liveTranscriptSegments = emptyList(),
)
