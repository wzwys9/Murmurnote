package app.murmurnote.android.audio

import app.murmurnote.android.util.Logger
import com.arthenica.ffmpegkit.FFprobeKit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFileInspector @Inject constructor(
    private val logger: Logger
) {

    data class Info(
        val durationMs: Long,
        val channels: Int,
        val sampleRate: Int,
        val codec: String?
    )

    fun inspect(file: File): Info {
        val session = FFprobeKit.getMediaInformation(file.absolutePath)
        val info = session.mediaInformation
        if (info == null) {
            // ffprobe 拿不到任何元数据：通常是文件损坏或被占用。把这一行落到日志里
            // 比让上层"durationMs=0 → AudioSplitter 直接 error"再回溯要快得多。
            logger.w("Inspect", "ffprobe returned no media info for ${file.name} size=${file.length()}")
            return Info(0, 0, 0, null)
        }
        val durationSec = info.duration?.toDoubleOrNull() ?: 0.0
        val stream = info.streams?.firstOrNull { it.type == "audio" }
        val sampleRate = stream?.sampleRate?.toString()?.toIntOrNull() ?: 0
        val channels = (stream?.getStringProperty("channels"))?.toIntOrNull() ?: 0
        val codec = stream?.codec
        val out = Info(
            durationMs = (durationSec * 1000).toLong(),
            channels = channels,
            sampleRate = sampleRate,
            codec = codec
        )
        // 异常元数据（无音轨 / 时长为 0）按 W 级别落日志，这两类输入会导致 Pipeline 后续阶段失败。
        if (stream == null) {
            logger.w("Inspect", "${file.name}: ffprobe found no audio stream (codec=${info.streams?.joinToString { it.type ?: "?" }})")
        } else if (out.durationMs <= 0) {
            logger.w("Inspect", "${file.name}: zero duration (codec=$codec sr=$sampleRate ch=$channels)")
        } else {
            logger.d("Inspect", "${file.name} dur=${out.durationMs}ms codec=$codec sr=$sampleRate ch=$channels")
        }
        return out
    }

    fun durationMs(file: File): Long = inspect(file).durationMs
}
