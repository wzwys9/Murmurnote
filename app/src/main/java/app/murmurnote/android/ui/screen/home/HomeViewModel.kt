package app.murmurnote.android.ui.screen.home

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.audio.AudioImporter
import app.murmurnote.android.audio.AudioRecorder
import app.murmurnote.android.audio.RecordingController
import app.murmurnote.android.data.asr.AsrEngine
import app.murmurnote.android.data.asr.AsrEngineProvider
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.data.local.entity.RecordingSegmentStatus
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.PipelineStatusBus
import app.murmurnote.android.domain.pipeline.ProcessingQueueEntry
import app.murmurnote.android.domain.pipeline.ProcessingQueueTracker
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val recordingRepository: RecordingRepository,
    private val recordingController: RecordingController,
    private val audioImporter: AudioImporter,
    private val asrEngineProvider: AsrEngineProvider,
    private val llmClient: LlmClient,
    private val appPreferences: AppPreferences,
    private val statusBus: PipelineStatusBus,
    private val queueTracker: ProcessingQueueTracker,
    private val logger: Logger
) : ViewModel() {

    companion object {
        private const val LIVE_SEGMENT_AUTO_RETRIES = 1
        private const val LOW_BATTERY_THRESHOLD_PERCENT = 20
    }

    private data class DraftPolicy(
        val enabled: Boolean,
        val minNewChars: Int,
        val minIntervalMs: Long
    )

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
    private var liveTranscriptionJob: Job? = null
    private var draftSummaryJob: Job? = null
    private var activeRecordingId: String? = null
    private val pendingDraftTranscript = StringBuilder()
    private var lastDraftSummaryAtMs: Long = 0L
    private var realtimePerformanceMode: String = "BALANCED"
    private var lowBatteryProtection: Boolean = true

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
        viewModelScope.launch {
            appPreferences.realtimePerformanceMode.collect { realtimePerformanceMode = it }
        }
        viewModelScope.launch {
            appPreferences.lowBatteryProtection.collect { lowBatteryProtection = it }
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
        viewModelScope.launch {
            recordingController.start()
                .onSuccess { active ->
                    runCatching {
                        recordingRepository.insert(
                            Recording(
                                id = active.id,
                                title = "录音中",
                                originalFilePath = active.file.absolutePath,
                                durationMs = 0L,
                                createdAt = active.createdAt,
                                source = RecordingSource.RECORDED,
                                processingStatus = ProcessingStatus.RECORDING,
                                expirationDate = active.createdAt + 30L * 24 * 3600 * 1000
                            )
                        )
                    }.onFailure { e ->
                        recordingController.cancel()
                        logger.e("Home", "failed to create recording draft", e)
                        _uiState.update { it.copy(errorMessage = "创建录音记录失败：${e.message ?: e.javaClass.simpleName}") }
                        return@onSuccess
                    }
                    activeRecordingId = active.id
                    pendingDraftTranscript.clear()
                    lastDraftSummaryAtMs = 0L
                    _uiState.update {
                        it.copy(
                            isRecording = true,
                            isPaused = false,
                            errorMessage = null,
                            elapsedMs = 0,
                            liveTranscriptionActive = false,
                            liveTranscriptionMessage = "等待说话后的停顿…",
                            liveTranscriptSegments = emptyList()
                        )
                    }
                    startTicker()
                    startLiveTranscription()
                }
                .onFailure { e ->
                    logger.e("Home", "startRecording failed", e)
                    _uiState.update { it.copy(errorMessage = "录音启动失败：${e.message ?: e.javaClass.simpleName}") }
                }
        }
    }

    fun stopRecording() {
        if (!recordingController.isRecording) return
        logger.i("Home", "stopRecording requested elapsed=${recordingController.elapsedMs()}ms")
        tickerJob?.cancel()
        stopLiveTranscription()
        recordingController.stopAndSubmit()
            .onSuccess { f ->
                logger.i("Home", "submitted to pipeline file=${f.absolutePath} size=${f.length()}")
                activeRecordingId = null
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        isPaused = false,
                        elapsedMs = 0,
                        errorMessage = null,
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = null
                    )
                }
            }
            .onFailure { e ->
                logger.e("Home", "stopRecording failed", e)
                activeRecordingId?.let { id ->
                    viewModelScope.launch { recordingRepository.delete(id) }
                }
                activeRecordingId = null
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
        stopLiveTranscription()
        recordingController.cancel()
        activeRecordingId?.let { id ->
            viewModelScope.launch { recordingRepository.delete(id) }
        }
        activeRecordingId = null
        pendingDraftTranscript.clear()
        draftSummaryJob?.cancel()
        draftSummaryJob = null
        _uiState.update {
            it.copy(
                isRecording = false,
                isPaused = false,
                elapsedMs = 0,
                liveTranscriptionActive = false,
                liveTranscriptionMessage = null,
                liveTranscriptSegments = emptyList()
            )
        }
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

    fun cancelCurrentProcessing(context: Context) {
        logger.w("Home", "cancelCurrentProcessing requested")
        ContextCompat.startForegroundService(context, TranscriptionService.cancelCurrentIntent(context))
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun retryLiveSegment(sequence: Int) {
        if (!recordingController.isRecording) return
        viewModelScope.launch {
            val segment = recordingController.recordedSegments().firstOrNull { it.sequence == sequence }
            if (segment == null) {
                _uiState.update { it.copy(liveTranscriptionMessage = "第 ${sequence + 1} 段音频已不可用") }
                return@launch
            }
            val engine = currentLocalLiveEngine() ?: return@launch
            logger.i("Home", "retry live segment $sequence")
            persistRecordingSegment(segment, RecordingSegmentStatus.READY)
            transcribeLiveSegment(segment, engine)
        }
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

    private fun startLiveTranscription() {
        liveTranscriptionJob?.cancel()
        liveTranscriptionJob = viewModelScope.launch {
            if (realtimePerformanceMode == "OFF") {
                _uiState.update {
                    it.copy(
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = "实时处理已关闭；停止后会完整转写。"
                    )
                }
                return@launch
            }
            val engine = currentLocalLiveEngine() ?: return@launch

            val processedSequences = mutableSetOf<Int>()
            _uiState.update {
                it.copy(
                    liveTranscriptionActive = true,
                    liveTranscriptionMessage = "等待说话后的停顿…"
                )
            }
            while (isActive && recordingController.isRecording) {
                val pending = recordingController.recordedSegments()
                    .filter { it.sequence !in processedSequences && it.file.exists() && it.file.length() > 44L }
                    .sortedBy { it.sequence }
                if (pending.isEmpty()) {
                    delay(1_000)
                    continue
                }
                pending.forEach { segment ->
                    processedSequences += segment.sequence
                    persistRecordingSegment(
                        segment = segment,
                        status = RecordingSegmentStatus.READY
                    )
                    transcribeLiveSegment(segment, engine)
                }
            }
        }
    }

    private suspend fun currentLocalLiveEngine(): AsrEngine? =
        when (val selection = asrEngineProvider.current()) {
            is AsrEngineProvider.Selection.NotReady -> {
                _uiState.update {
                    it.copy(
                        liveTranscriptionActive = false,
                        liveTranscriptionMessage = selection.reason
                    )
                }
                null
            }
            is AsrEngineProvider.Selection.Active -> {
                if (selection.engine.engineType == AsrEngineType.CLOUD_GLM) {
                    _uiState.update {
                        it.copy(
                            liveTranscriptionActive = false,
                            liveTranscriptionMessage = "录音中预览当前仅支持本地 ASR；停止后会完整转写。"
                        )
                    }
                    null
                } else {
                    selection.engine
                }
            }
        }

    private suspend fun transcribeLiveSegment(
        segment: AudioRecorder.RecordedSegment,
        engine: AsrEngine,
        attempt: Int = 0
    ) {
        upsertLiveSegment(
            LiveTranscriptSegment(
                sequence = segment.sequence,
                startMs = segment.startMs,
                endMs = segment.endMs,
                status = LiveTranscriptStatus.TRANSCRIBING,
                progress = 0f
            )
        )
        persistRecordingSegment(
            segment = segment,
            status = RecordingSegmentStatus.TRANSCRIBING
        )
        _uiState.update {
            it.copy(
                liveTranscriptionActive = true,
                liveTranscriptionMessage = "正在转写第 ${segment.sequence + 1} 段…"
            )
        }

        val result = engine.transcribe(segment.file) { progress ->
            upsertLiveSegment(
                LiveTranscriptSegment(
                    sequence = segment.sequence,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    status = LiveTranscriptStatus.TRANSCRIBING,
                    progress = progress
                )
            )
        }
        result
            .onSuccess { asr ->
                persistRecordingSegment(
                    segment = segment,
                    status = RecordingSegmentStatus.TRANSCRIBED,
                    transcriptText = asr.text
                )
                upsertLiveSegment(
                    LiveTranscriptSegment(
                        sequence = segment.sequence,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        status = LiveTranscriptStatus.TRANSCRIBED,
                        text = asr.text,
                        progress = 1f
                    )
                )
                _uiState.update {
                    it.copy(liveTranscriptionMessage = "已实时转写 ${it.liveTranscriptSegments.count { seg -> seg.status == LiveTranscriptStatus.TRANSCRIBED }} 段")
                }
                logger.i("Home", "live segment ${segment.sequence} transcribed chars=${asr.text.length}")
                maybeUpdateDraftSummary(asr.text)
            }
            .onFailure { e ->
                if (attempt < LIVE_SEGMENT_AUTO_RETRIES) {
                    _uiState.update {
                        it.copy(liveTranscriptionMessage = "第 ${segment.sequence + 1} 段转写失败，正在自动重试…")
                    }
                    logger.w("Home", "live segment ${segment.sequence} failed, retrying: ${e.message}")
                    delay(1_000)
                    transcribeLiveSegment(segment, engine, attempt + 1)
                    return
                }
                persistRecordingSegment(
                    segment = segment,
                    status = RecordingSegmentStatus.FAILED,
                    errorMessage = e.message ?: e.javaClass.simpleName
                )
                upsertLiveSegment(
                    LiveTranscriptSegment(
                        sequence = segment.sequence,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        status = LiveTranscriptStatus.FAILED,
                        errorMessage = e.message ?: e.javaClass.simpleName
                    )
                )
                _uiState.update { it.copy(liveTranscriptionMessage = "第 ${segment.sequence + 1} 段实时转写失败") }
                logger.w("Home", "live segment ${segment.sequence} failed: ${e.message}")
            }
    }

    private fun upsertLiveSegment(segment: LiveTranscriptSegment) {
        _uiState.update { state ->
            state.copy(
                liveTranscriptSegments = (state.liveTranscriptSegments.filterNot { it.sequence == segment.sequence } + segment)
                    .sortedBy { it.sequence }
            )
        }
    }

    private suspend fun persistRecordingSegment(
        segment: AudioRecorder.RecordedSegment,
        status: RecordingSegmentStatus,
        errorMessage: String? = null,
        transcriptText: String? = null
    ) {
        val recordingId = activeRecordingId ?: return
        runCatching {
            recordingRepository.insertRecordingSegment(
                RecordingSegment(
                    recordingId = recordingId,
                    sequence = segment.sequence,
                    filePath = segment.file.absolutePath,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    status = status,
                    errorMessage = errorMessage,
                    transcriptText = transcriptText
                )
            )
        }.onFailure { e ->
            logger.w("Home", "failed to persist live segment ${segment.sequence}: ${e.message}")
        }
    }

    private fun maybeUpdateDraftSummary(newTranscript: String) {
        val cleaned = newTranscript.trim()
        if (cleaned.isBlank()) return
        pendingDraftTranscript.appendLine(cleaned)
        val policy = currentDraftPolicy()
        if (!policy.enabled) return

        val now = System.currentTimeMillis()
        val shouldUpdate =
            lastDraftSummaryAtMs == 0L ||
                pendingDraftTranscript.length >= policy.minNewChars ||
                now - lastDraftSummaryAtMs >= policy.minIntervalMs
        if (!shouldUpdate || draftSummaryJob?.isActive == true) return

        draftSummaryJob = viewModelScope.launch {
            val recordingId = activeRecordingId ?: return@launch
            val snapshotLength = pendingDraftTranscript.length
            val newText = pendingDraftTranscript.toString().trim()
            if (newText.isBlank()) return@launch
            val rec = recordingRepository.get(recordingId) ?: return@launch
            llmClient.updateDraftSummary(rec.draftSummary, newText)
                .onSuccess { summary ->
                    val latest = recordingRepository.get(recordingId) ?: return@onSuccess
                    recordingRepository.update(
                        latest.copy(
                            draftSummary = summary,
                            summary = if (latest.finalSummary == null) summary else latest.summary
                        )
                    )
                    pendingDraftTranscript.delete(0, minOf(snapshotLength, pendingDraftTranscript.length))
                    lastDraftSummaryAtMs = System.currentTimeMillis()
                    logger.i("Home", "draft summary updated chars=${summary.length}")
                }
                .onFailure { e ->
                    logger.w("Home", "draft summary update failed: ${e.message}")
                }
        }
    }

    private fun currentDraftPolicy(): DraftPolicy {
        if (lowBatteryProtection && batteryPercent() in 0 until LOW_BATTERY_THRESHOLD_PERCENT) {
            return DraftPolicy(enabled = false, minNewChars = Int.MAX_VALUE, minIntervalMs = Long.MAX_VALUE)
        }
        return when (realtimePerformanceMode) {
            "OFF" -> DraftPolicy(enabled = false, minNewChars = Int.MAX_VALUE, minIntervalMs = Long.MAX_VALUE)
            "POWER_SAVE" -> DraftPolicy(enabled = true, minNewChars = 1_200, minIntervalMs = 5L * 60 * 1000)
            "FAST" -> DraftPolicy(enabled = true, minNewChars = 300, minIntervalMs = 60_000)
            else -> DraftPolicy(enabled = true, minNewChars = 600, minIntervalMs = 2L * 60 * 1000)
        }
    }

    private fun batteryPercent(): Int {
        val bm = appContext.getSystemService(BatteryManager::class.java) ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun stopLiveTranscription() {
        liveTranscriptionJob?.cancel()
        liveTranscriptionJob = null
    }

    override fun onCleared() {
        tickerJob?.cancel()
        stopLiveTranscription()
        draftSummaryJob?.cancel()
    }
}
