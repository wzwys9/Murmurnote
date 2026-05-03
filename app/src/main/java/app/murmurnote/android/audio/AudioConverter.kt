package app.murmurnote.android.audio

import android.os.SystemClock
import app.murmurnote.android.util.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把任意输入音频转成 GLM-ASR 可接受的格式：
 * - 单声道（GLM 只支持 mono；NOTES 第一节实测）
 * - 16kHz / PCM 16-bit / WAV 容器（GLM 文件白名单）
 *
 * 文件名为 *.wav；用 ffmpeg-kit 异步执行避免阻塞主线程。
 */
@Singleton
class AudioConverter @Inject constructor(
    private val logger: Logger
) {

    /**
     * @param input 任意支持的输入（m4a/mp3/wav/flac/ogg/opus/aac 等）
     * @param outputDir 输出目录
     * @return 转换后的 mono WAV 文件
     */
    suspend fun convertToMonoWav(input: File, outputDir: File): File {
        outputDir.mkdirs()
        val output = File(outputDir, "${input.nameWithoutExtension}_mono16k.wav")
        if (output.exists()) output.delete()

        val cmd = buildString {
            append("-y -i ").append(quote(input.absolutePath))
            append(" -vn")                       // 去掉视频流（如果有）
            append(" -ac 1")                     // 单声道
            append(" -ar 16000")                 // 16kHz
            append(" -c:a pcm_s16le")            // PCM 16-bit
            append(" -f wav ")
            append(quote(output.absolutePath))
        }
        logger.i("Convert", "begin ${input.name} (size=${input.length()}) → ${output.name}")

        val started = SystemClock.elapsedRealtime()
        val deferred = CompletableDeferred<Unit>()
        FFmpegKit.executeAsync(cmd) { session ->
            val rc = session.returnCode
            if (ReturnCode.isSuccess(rc)) {
                deferred.complete(Unit)
            } else {
                val tail = session.allLogsAsString.takeLast(800)
                logger.e("Convert", "ffmpeg failed rc=$rc input=${input.name} tail=$tail")
                deferred.completeExceptionally(
                    IllegalStateException("ffmpeg convert failed: rc=$rc, log=$tail")
                )
            }
        }
        deferred.await()
        check(output.exists() && output.length() > 0) { "ffmpeg produced empty output: ${output.absolutePath}" }
        logger.i(
            "Convert",
            "done ${input.name} → ${output.name} size=${output.length()} elapsed=${SystemClock.elapsedRealtime() - started}ms"
        )
        return output
    }

    private fun quote(path: String): String = "'${path.replace("'", "'\\''")}'"
}
