package app.murmurnote.android.domain.pipeline

import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service → UI 的 Pipeline 状态总线。
 * - TranscriptionService 在每个 stage 写入
 * - HomeViewModel 订阅渲染进度卡片
 * - Completed 状态保留 4 秒（让用户看到"完成"）后自动归 Idle
 * - Failed 状态需要用户调 dismiss() 才清
 */
@Singleton
class PipelineStatusBus @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val logger: Logger
) {
    private val _stage = MutableStateFlow<PipelineStage>(PipelineStage.Idle)
    val stage: StateFlow<PipelineStage> = _stage.asStateFlow()

    private var autoClearJob: Job? = null

    fun update(s: PipelineStage) {
        autoClearJob?.cancel()
        _stage.value = s
        logger.i("Bus", "stage = ${describe(s)}")
        if (s is PipelineStage.Completed) {
            autoClearJob = scope.launch {
                delay(4000)
                _stage.value = PipelineStage.Idle
            }
        }
    }

    fun dismiss() {
        autoClearJob?.cancel()
        _stage.value = PipelineStage.Idle
    }

    private fun describe(s: PipelineStage): String = when (s) {
        is PipelineStage.Idle -> "Idle"
        is PipelineStage.Recording -> "Recording(${s.durationMs}ms)"
        is PipelineStage.Converting -> "Converting(${(s.progress * 100).toInt()}%)"
        is PipelineStage.Splitting -> "Splitting(count=${s.segmentCount})"
        is PipelineStage.Transcribing -> "Transcribing(${s.segmentIndex + 1}/${s.totalSegments}, partial=${s.partialText.take(30)})"
        is PipelineStage.Extracting -> "Extracting(textLen=${s.transcriptLength})"
        is PipelineStage.Saving -> "Saving(${s.recordingId.take(8)})"
        is PipelineStage.Completed -> "Completed(${s.recordingId.take(8)})"
        is PipelineStage.Failed -> "Failed(${s.stage}: ${s.errorMessage.take(80)})"
    }
}
