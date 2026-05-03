package app.murmurnote.android.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import app.murmurnote.android.util.Logger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayer @Inject constructor(
    private val logger: Logger
) {

    private var player: MediaPlayer? = null
    private var prepared: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var pendingSeek: Long? = null
    private var pendingPlay: Boolean = false
    private var onComplete: (() -> Unit)? = null

    fun load(file: File, onPrepared: (durationMs: Int) -> Unit) {
        release()
        prepared = false
        pendingPlay = false
        val mp = MediaPlayer()
        mp.setDataSource(file.absolutePath)
        mp.setOnPreparedListener {
            prepared = true
            onPrepared(mp.duration)
            pendingSeek?.let { mp.seekTo(it.toInt()); pendingSeek = null }
            // 关键：不要在这里调 setPlaybackParams，那会导致 MediaPlayer 立刻 start（自动播放 bug）。
            // 倍速只在用户显式切换时再 apply（applySpeedNow）。
            if (pendingPlay) {
                pendingPlay = false
                mp.start()
            }
            logger.i("Player", "prepared ${file.name} dur=${mp.duration}ms")
        }
        mp.setOnCompletionListener {
            logger.i("Player", "completed ${file.name}")
            onComplete?.invoke()
        }
        mp.setOnErrorListener { _, what, extra ->
            logger.e("Player", "MediaPlayer error what=$what extra=$extra file=${file.name}")
            true
        }
        mp.prepareAsync()
        player = mp
    }

    fun setOnComplete(cb: () -> Unit) { onComplete = cb }

    fun play() {
        val mp = player ?: return
        if (!prepared) {
            pendingPlay = true   // 等 onPrepared 自动 start
            return
        }
        if (!mp.isPlaying) mp.start()
    }

    fun pause() {
        val mp = player ?: return
        pendingPlay = false
        if (prepared && mp.isPlaying) mp.pause()
    }

    fun isPlaying(): Boolean = prepared && (player?.isPlaying == true)

    fun positionMs(): Int = if (prepared) player?.currentPosition ?: 0 else 0
    fun durationMs(): Int = if (prepared) player?.duration ?: 0 else 0

    fun seekTo(ms: Long) {
        val mp = player
        if (mp == null || !prepared) { pendingSeek = ms; return }
        mp.seekTo(ms.toInt())
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        applySpeedNow(speed)
    }

    /** 仅在 prepared 且当前正在播时安全 apply。否则会触发"自动开始播放"副作用。 */
    private fun applySpeedNow(speed: Float) {
        val mp = player ?: return
        if (!prepared) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val params = mp.playbackParams ?: PlaybackParams()
        try {
            mp.playbackParams = params.setSpeed(speed)
        } catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {}
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prepared = false
        pendingPlay = false
        pendingSeek = null
    }
}
