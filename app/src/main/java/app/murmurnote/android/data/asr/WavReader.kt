package app.murmurnote.android.data.asr

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 极简 WAV(PCM 16-bit, mono, 16kHz) 读取器，回 FloatArray (-1.0..1.0)。
 *
 * 之所以不复用 ffmpeg：Pipeline 进到 LocalAsrEngine 之前已经被 AudioConverter 转成
 * mono / 16kHz / PCM 16-bit / WAV（NOTES 第一节硬约束），文件结构非常规整，
 * 直接二进制读 44-byte header + samples 比再起一次 ffmpeg 进程快两个数量级。
 *
 * 兼容容差：
 *   - 接受标准 RIFF/WAVE header（44 字节）
 *   - 如果是非标准 fmt chunk 长度 / 含 LIST chunk，按 chunk 头跳到 data chunk
 *   - 仅支持 mono 16-bit；非此组合直接抛错（按理上游 AudioConverter 已经强制为 mono 16k 16-bit）
 */
object WavReader {

    const val TARGET_SR = 16000
    private const val PCM_FORMAT = 1.toShort()

    fun readMono16kPcm(file: File): FloatArray {
        DataInputStream(FileInputStream(file).buffered()).use { dis ->
            val riff = ByteArray(4).also { dis.readFully(it) }
            require(String(riff) == "RIFF") { "不是 RIFF 文件：${file.name}" }
            dis.skipBytes(4) // file size
            val wave = ByteArray(4).also { dis.readFully(it) }
            require(String(wave) == "WAVE") { "不是 WAVE 文件：${file.name}" }

            var sampleRate = 0
            var channels: Short = 0
            var bitsPerSample: Short = 0
            var format: Short = 0
            var dataLen = 0

            // 滚动找 fmt 与 data chunk
            while (dis.available() > 8) {
                val id = ByteArray(4).also { dis.readFully(it) }
                val size = readIntLE(dis)
                when (String(id)) {
                    "fmt " -> {
                        format = readShortLE(dis)
                        channels = readShortLE(dis)
                        sampleRate = readIntLE(dis)
                        dis.skipBytes(4) // byte rate
                        dis.skipBytes(2) // block align
                        bitsPerSample = readShortLE(dis)
                        // fmt chunk 可能比 16 长（带扩展），跳掉剩余
                        val consumed = 16
                        if (size > consumed) dis.skipBytes(size - consumed)
                    }
                    "data" -> {
                        dataLen = size
                        break
                    }
                    else -> dis.skipBytes(size + (size and 1)) // chunk 按字节对齐到偶数
                }
            }

            require(format == PCM_FORMAT) { "WAV 格式不是 PCM(format=$format)" }
            require(channels.toInt() == 1) { "WAV 不是单声道(channels=$channels)" }
            require(bitsPerSample.toInt() == 16) { "WAV 不是 16-bit(bps=$bitsPerSample)" }
            require(sampleRate == TARGET_SR) { "WAV 采样率不是 ${TARGET_SR}Hz(实际=$sampleRate)" }
            require(dataLen > 0) { "WAV data chunk 为空：${file.name}" }

            val sampleCount = dataLen / 2
            val out = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val lo = dis.readUnsignedByte()
                val hi = dis.readByte().toInt()
                val s16 = (hi shl 8) or lo
                out[i] = s16 / 32768f
            }
            return out
        }
    }

    private fun readIntLE(dis: DataInputStream): Int {
        val a = dis.readUnsignedByte()
        val b = dis.readUnsignedByte()
        val c = dis.readUnsignedByte()
        val d = dis.readUnsignedByte()
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }

    private fun readShortLE(dis: DataInputStream): Short {
        val a = dis.readUnsignedByte()
        val b = dis.readUnsignedByte()
        return ((b shl 8) or a).toShort()
    }
}
