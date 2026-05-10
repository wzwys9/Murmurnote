package app.murmurnote.android.audio

import android.content.Context
import android.content.Intent
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val logger: Logger
) {
    data class ActiveRecording(
        val id: String,
        val file: File,
        val createdAt: Long
    )

    private var activeRecording: ActiveRecording? = null

    val isRecording: Boolean get() = recorder.isRecording
    val isPaused: Boolean get() = recorder.isPaused
    fun elapsedMs(): Long = recorder.elapsedMs()
    fun amplitudeDb(): Int = recorder.amplitudeDb()
    fun recordedSegments(): List<AudioRecorder.RecordedSegment> = recorder.recordedSegments()

    fun start(): Result<ActiveRecording> = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val target = File(dir, "rec_$ts.wav")
        val active = ActiveRecording(
            id = UUID.randomUUID().toString(),
            file = target,
            createdAt = System.currentTimeMillis()
        )
        recorder.start(target)
        activeRecording = active
        logger.i("Rec", "start → ${target.absolutePath}")
        active
    }.onFailure { logger.e("Rec", "start failed", it) }

    fun pause() {
        recorder.pause()
        logger.i("Rec", "pause @ ${elapsedMs()}ms")
    }

    fun resume() {
        recorder.resume()
        logger.i("Rec", "resume")
    }

    fun cancel() {
        recorder.cancel()
        activeRecording = null
        logger.i("Rec", "cancelled")
    }

    /** 停止并把文件交给 Pipeline 异步处理。返回文件 size 以便 UI 反馈。 */
    fun stopAndSubmit(): Result<File> = runCatching {
        val active = activeRecording
        val file = recorder.stop() ?: error("recorder not running")
        activeRecording = null
        logger.i("Rec", "stop → ${file.absolutePath} size=${file.length()}")
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
}
