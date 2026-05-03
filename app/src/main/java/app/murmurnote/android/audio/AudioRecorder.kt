package app.murmurnote.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10

@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger
) {
    private var recorder: MediaRecorder? = null
    private var startedAtMs: Long = 0
    private var pausedAccumulatedMs: Long = 0
    private var pauseStartMs: Long = 0
    private var currentFile: File? = null
    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var isPaused: Boolean = false
        private set

    fun start(target: File): File {
        check(!isRecording) { "Recorder already started" }
        target.parentFile?.mkdirs()
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioChannels(1)
        mr.setAudioSamplingRate(16000)
        mr.setAudioEncodingBitRate(64_000)
        mr.setOutputFile(target.absolutePath)
        try {
            mr.prepare()
            mr.start()
        } catch (t: Throwable) {
            // MediaRecorder.start 抛 IllegalStateException 是常见症状（麦克风被占用 / OEM 限制 / 录音权限黑名单）；
            // 先把异常落日志，再让调用方处理。否则上层只看到一个空 Result.failure 没有线索。
            logger.e("Rec", "MediaRecorder.start failed for ${target.name}", t)
            runCatching { mr.release() }
            throw t
        }
        recorder = mr
        currentFile = target
        startedAtMs = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0
        isRecording = true
        isPaused = false
        return target
    }

    fun pause() {
        if (!isRecording || isPaused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.pause()
            isPaused = true
            pauseStartMs = SystemClock.elapsedRealtime()
        }
    }

    fun resume() {
        if (!isRecording || !isPaused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            recorder?.resume()
            pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartMs
            isPaused = false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        val result = runCatching {
            recorder?.stop()
            recorder?.release()
            currentFile
        }
        recorder = null
        isRecording = false
        isPaused = false
        // stop 在录音 < 1s 时会抛 IllegalStateException，文件可能未写完；记日志便于排查
        // 用户那种"按住就松开"的误触发到底是录到一段还是 0 字节文件。
        result.exceptionOrNull()?.let { logger.w("Rec", "MediaRecorder.stop threw (likely <1s recording)", it) }
        return result.getOrNull()
    }

    fun cancel() {
        val cancelledFile = currentFile?.absolutePath
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        currentFile?.delete()
        recorder = null
        currentFile = null
        isRecording = false
        isPaused = false
        if (cancelledFile != null) logger.d("Rec", "low-level cancel cleared $cancelledFile")
    }

    fun elapsedMs(): Long {
        if (!isRecording) return 0
        val pausedExtra = if (isPaused) SystemClock.elapsedRealtime() - pauseStartMs else 0
        return SystemClock.elapsedRealtime() - startedAtMs - pausedAccumulatedMs - pausedExtra
    }

    fun amplitudeDb(): Int {
        val amp = recorder?.maxAmplitude ?: 0
        return if (amp <= 0) 0 else (20 * log10(amp.toDouble())).toInt().coerceIn(0, 100)
    }
}
