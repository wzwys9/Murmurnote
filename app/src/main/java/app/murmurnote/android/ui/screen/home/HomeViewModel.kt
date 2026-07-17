package app.murmurnote.android.ui.screen.home

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.R
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
import app.murmurnote.android.util.localizedString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
        _uiState.update { current ->
            restoreRecordingUiState(
                current,
                session,
                context.localizedString(R.string.home_recording_in_background),
            )
        }
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
            logger.e("Home", "prepareForRecording failed", error)
            _uiState.update {
                it.copy(
                    errorMessage = context.localizedString(R.string.home_recording_start_failed)
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
                                errorMessage = context.localizedString(R.string.home_recording_start_failed)
                            )
                        }
                        return@launch
                    }
                startupRecordingId = active.id
                try {
                    recordingRepository.insert(
                        Recording(
                            id = active.id,
                            title = context.localizedString(R.string.home_recording_draft_title),
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
                            errorMessage = context.localizedString(R.string.home_create_recording_failed)
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
                        liveTranscriptionMessage = context.localizedString(R.string.home_live_vad_starting),
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
            it.copy(
                liveTranscriptionActive = false,
                liveTranscriptionMessage = context.localizedString(R.string.home_saving_recording),
            )
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
                        context.localizedString(R.string.home_processing_start_failed_saved)
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
                        errorMessage = context.localizedString(R.string.home_processing_start_failed)
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
                    it.copy(
                        errorMessage = context.localizedString(R.string.home_cancel_failed)
                    )
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
        _uiState.update {
            it.copy(errorMessage = context.localizedString(R.string.home_record_permission_denied))
        }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            logger.i("Home", "importAudio requested")
            audioImporter.importAndProcess(uri)
                .onSuccess { f ->
                    _uiState.update { it.copy(errorMessage = null) }
                }
                .onFailure { e ->
                    logger.e("Home", "importAudio failed", e)
                    _uiState.update {
                        it.copy(
                            errorMessage = context.localizedString(R.string.home_import_failed)
                        )
                    }
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

    fun retryLiveSegment(sequence: Int) {
        val segment = recordingController.recordedSegments()
            .firstOrNull { it.sequence == sequence }
        if (segment == null || !recordingController.isRecording) {
            _uiState.update {
                it.copy(
                    liveTranscriptionMessage =
                        context.localizedString(R.string.home_preview_segment_unavailable)
                )
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
        _uiState.update {
            it.copy(
                liveTranscriptionMessage = context.localizedString(
                    R.string.home_preview_retry_scheduled,
                    sequence + 1,
                )
            )
        }
    }

    private fun startLiveTranscription() {
        stopLiveTranscription()
        val generation = livePreviewGeneration
        liveRetryQueue.clear()
        val job = viewModelScope.launch(Dispatchers.IO) {
            val attempt = when (
                val selection = asrEngineProvider.snapshotAttempt(
                    vadPresetVersion = NeuralVadSegmentPlanner.PRESET.version,
                    locale = context.resources.configuration.locales[0]
                )
            ) {
                is AsrEngineProvider.AttemptSelection.NotReady -> {
                    updateLiveUi(generation) {
                        it.copy(
                            liveTranscriptionActive = false,
                            liveTranscriptionMessage =
                            context.localizedString(R.string.home_live_preview_unavailable)
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
                            context.localizedString(R.string.home_cloud_live_preview_disabled)
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
                liveTranscriptionMessage = context.localizedString(
                    R.string.home_previewing_segment,
                    segment.sequence + 1,
                )
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
                            context.localizedString(
                                R.string.home_previewed_segments,
                                state.liveTranscriptSegments.count {
                                    it.status == LiveTranscriptStatus.TRANSCRIBED
                                },
                            )
                    )
                }
            },
            onFailure = { failure ->
                if (!isLivePreviewCurrent(generation)) return@fold
                if (retryAttempt < LIVE_SEGMENT_AUTO_RETRIES) {
                    updateLiveUi(generation) {
                        it.copy(
                            liveTranscriptionMessage = context.localizedString(
                                R.string.home_preview_segment_retrying,
                                segment.sequence + 1,
                            )
                        )
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
                        errorMessage = context.localizedString(R.string.home_transcription_failed)
                    )
                )
                updateLiveUi(generation) {
                    it.copy(
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage =
                            context.localizedString(
                                R.string.home_preview_segment_failed,
                                segment.sequence + 1,
                            )
                    )
                }
            }
        )
    }

    private fun updateLiveVadStatus(generation: Long, segmentCount: Int) {
        val snapshot = recordingController.liveVadSnapshot()
        val (active, message) = when (snapshot.state) {
            LiveVadWorkerState.NEW,
            LiveVadWorkerState.STARTING -> true to
                context.localizedString(R.string.home_live_vad_starting)

            LiveVadWorkerState.RUNNING -> true to if (segmentCount == 0) {
                context.localizedString(R.string.home_vad_listening)
            } else {
                context.localizedString(R.string.home_preview_segments_created, segmentCount)
            }

            LiveVadWorkerState.FINISHING -> true to
                context.localizedString(R.string.home_preview_finishing)
            LiveVadWorkerState.STOPPED -> false to
                context.localizedString(R.string.home_preview_finished)
            LiveVadWorkerState.DISABLED_BACKPRESSURE -> false to
                context.localizedString(R.string.home_preview_backpressure)
            LiveVadWorkerState.FAILED_NEURAL_VAD -> false to
                context.localizedString(R.string.home_preview_vad_failed)
            LiveVadWorkerState.ABORTED -> false to
                context.localizedString(R.string.home_preview_cancelled)
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
    liveTranscriptionMessage: String,
): HomeViewModel.UiState = current.copy(
    isRecording = true,
    isPaused = session.isPaused,
    elapsedMs = session.elapsedMs,
    amplitudeDb = session.amplitudeDb,
    errorMessage = null,
    liveTranscriptionActive = false,
    liveTranscriptionMessage = liveTranscriptionMessage,
    liveTranscriptSegments = emptyList(),
)
