package app.murmurnote.android.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class SizeLimitExceededException(
    val limitBytes: Long,
) : IOException("数据超过允许的最大大小：$limitBytes 字节")

internal object BoundedStreams {
    private const val BUFFER_BYTES = 64 * 1024

    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        onChunkCopied: (Long) -> Unit = {},
    ): Long {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        val buffer = ByteArray(BUFFER_BYTES)
        var copied = 0L
        var consecutiveEmptyReads = 0
        onChunkCopied(copied)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return copied
            if (read == 0) {
                if (++consecutiveEmptyReads >= MAX_EMPTY_READS) {
                    throw IOException("输入流连续返回空数据")
                }
                continue
            }
            consecutiveEmptyReads = 0
            if (copied > maxBytes - read) {
                throw SizeLimitExceededException(maxBytes)
            }
            output.write(buffer, 0, read)
            copied += read
            onChunkCopied(copied)
        }
    }

    fun readUtf8(input: InputStream, maxBytes: Long): String {
        val output = ByteArrayOutputStream(maxBytes.coerceAtMost(8 * 1024L).toInt())
        copy(input, output, maxBytes)
        return output.toString(Charsets.UTF_8.name())
    }

    private const val MAX_EMPTY_READS = 16
}
