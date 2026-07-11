package app.murmurnote.android.audio

import android.content.Context
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.service.RecordingForegroundSession
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 录音控制器：开始 → AudioRecorder 写入 WAV 文件和滚动片段；停止 → 落盘 + 投递到 TranscriptionService 跑 Pipeline。
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: AudioRecorder,
    private val foregroundSession: RecordingForegroundSession,
    private val logger: Logger
) {
    data class ActiveRecording(
        val id: String,
        val file: File,
        val createdAt: Long
    )

    data class ActiveSession(
        val id: String,
        val file: File,
        val createdAt: Long,
        val isPaused: Boolean,
        val elapsedMs: Long,
        val amplitudeDb: Int,
    )

    @Volatile private var activeRecording: ActiveRecording? = null

    val isRecording: Boolean get() = recorder.isRecording
    val isPaused: Boolean get() = recorder.isPaused
    fun elapsedMs(): Long = recorder.elapsedMs()
    fun amplitudeDb(): Int = recorder.amplitudeDb()
    fun activeSession(): ActiveSession? {
        val active = activeRecording ?: return null
        if (!recorder.isRecording) return null
        return ActiveSession(
            id = active.id,
            file = active.file,
            createdAt = active.createdAt,
            isPaused = recorder.isPaused,
            elapsedMs = recorder.elapsedMs(),
            amplitudeDb = recorder.amplitudeDb(),
        )
    }
    internal fun recordedSegments(): List<AudioRecorder.RecordedSegment> =
        recorder.recordedSegments()
    internal fun discardRecordedSegment(sequence: Int) =
        recorder.discardRecordedSegment(sequence)
    internal fun liveVadSnapshot(): LiveVadWorkerSnapshot = recorder.liveVadSnapshot()

    /** Must run directly from the visible, permission-granted user action on recent Android. */
    fun prepareForRecording(): Result<Unit> = runCatching {
        foregroundSession.start()
    }.onFailure { failure ->
        logger.e("Rec", "microphone foreground session start failed", failure)
    }

    /** Releases a prepared session when capture never reached the running state. */
    fun abortPreparedRecording() {
        stopForegroundSession()
    }

    fun start(): Result<ActiveRecording> = runCatching {
        check(foregroundSession.isStarted) { "录音前台服务尚未启动" }
        val recordingId = UUID.randomUUID().toString()
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "应用私有录音目录不可用"
        }
        val dir = File(externalRoot, "recordings").apply {
            check(isDirectory || mkdirs()) { "无法创建录音目录" }
        }
        val target = File(dir, "rec_$recordingId.wav")
        val active = ActiveRecording(
            id = recordingId,
            file = target,
            createdAt = System.currentTimeMillis()
        )
        recorder.start(target)
        activeRecording = active
        logger.i("Rec", "recording started")
        active
    }.onFailure {
        stopForegroundSession()
        logger.e("Rec", "start failed", it)
    }

    fun pause() {
        recorder.pause()
        logger.i("Rec", "pause @ ${elapsedMs()}ms")
    }

    fun resume() {
        recorder.resume()
        logger.i("Rec", "resume")
    }

    fun cancel() {
        try {
            recorder.cancel()
        } finally {
            activeRecording = null
            stopForegroundSession()
        }
        logger.i("Rec", "cancelled")
    }

    /** 停止并把文件交给 Pipeline 异步处理。返回文件 size 以便 UI 反馈。 */
    fun stopAndSubmit(): Result<File> = runCatching {
        val active = activeRecording
        val file = try {
            recorder.stop() ?: error("recorder not running")
        } finally {
            activeRecording = null
            stopForegroundSession()
        }
        logger.i("Rec", "recording stopped size=${file.length()}")
        if (!file.exists() || file.length() < 1024) error("录音文件过小，可能未真正录到声音")
        // 投递到前台服务做 Pipeline
        val intent = if (active != null) {
            TranscriptionService.reprocessIntent(context, file, RecordingSource.RECORDED, active.id)
        } else {
            TranscriptionService.intent(context, file, RecordingSource.RECORDED)
        }
        ContextCompat.startForegroundService(
            context,
            intent
        )
        file
    }.onFailure { logger.e("Rec", "stopAndSubmit failed", it) }

    private fun stopForegroundSession() {
        runCatching { foregroundSession.stop() }
            .onFailure { failure ->
                logger.w("Rec", "microphone foreground session stop failed", failure)
            }
    }
}
