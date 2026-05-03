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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 录音控制器：开始 → AudioRecorder 写入 m4a 文件；停止 → 落盘 + 投递到 TranscriptionService 跑 Pipeline。
 */
@Singleton
class RecordingController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: AudioRecorder,
    private val logger: Logger
) {
    val isRecording: Boolean get() = recorder.isRecording
    val isPaused: Boolean get() = recorder.isPaused
    fun elapsedMs(): Long = recorder.elapsedMs()
    fun amplitudeDb(): Int = recorder.amplitudeDb()

    fun start(): Result<Unit> = runCatching {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val target = File(dir, "rec_$ts.m4a")
        recorder.start(target)
        logger.i("Rec", "start → ${target.absolutePath}")
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
        logger.i("Rec", "cancelled")
    }

    /** 停止并把文件交给 Pipeline 异步处理。返回文件 size 以便 UI 反馈。 */
    fun stopAndSubmit(): Result<File> = runCatching {
        val file = recorder.stop() ?: error("recorder not running")
        logger.i("Rec", "stop → ${file.absolutePath} size=${file.length()}")
        if (!file.exists() || file.length() < 1024) error("录音文件过小，可能未真正录到声音")
        // 投递到前台服务做 Pipeline
        ContextCompat.startForegroundService(
            context,
            TranscriptionService.intent(context, file, RecordingSource.RECORDED)
        )
        file
    }.onFailure { logger.e("Rec", "stopAndSubmit failed", it) }
}
