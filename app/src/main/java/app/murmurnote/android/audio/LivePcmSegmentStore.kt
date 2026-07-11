package app.murmurnote.android.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Fixed-capacity PCM16 ring used by the live worker to materialize preview WAV files. It retains
 * only the newest samples needed by the 25-second planner window; a long meeting cannot grow heap
 * or a duplicate raw-audio spool. The lossless full WAV is owned independently by AudioRecorder.
 */
internal class LivePcmSegmentStore(
    val outputDirectory: File,
    private val sampleRateHz: Int,
    private val capacitySamples: Int = sampleRateHz * DEFAULT_CAPACITY_SECONDS,
) : Closeable {
    private val ring: ByteArray
    private var nextWriteByte = 0
    private var retainedSamples = 0
    private var closed = false

    var sampleCount: Int = 0
        private set

    val earliestRetainedSample: Int
        get() = sampleCount - retainedSamples

    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(capacitySamples > 0) { "Live PCM ring capacity must be positive" }
        outputDirectory.mkdirs()
        ring = ByteArray(Math.multiplyExact(capacitySamples, PCM16_BYTES_PER_SAMPLE))
    }

    fun append(pcm16Le: ByteArray) {
        check(!closed) { "Live PCM store is closed" }
        require(pcm16Le.size % PCM16_BYTES_PER_SAMPLE == 0) {
            "PCM16 input must contain complete samples"
        }
        val incomingSamples = pcm16Le.size / PCM16_BYTES_PER_SAMPLE
        val newSampleCount = Math.addExact(sampleCount, incomingSamples)
        if (pcm16Le.size >= ring.size) {
            pcm16Le.copyInto(
                destination = ring,
                destinationOffset = 0,
                startIndex = pcm16Le.size - ring.size,
                endIndex = pcm16Le.size,
            )
            nextWriteByte = 0
            retainedSamples = capacitySamples
        } else {
            var sourceOffset = 0
            while (sourceOffset < pcm16Le.size) {
                val length = minOf(pcm16Le.size - sourceOffset, ring.size - nextWriteByte)
                pcm16Le.copyInto(
                    destination = ring,
                    destinationOffset = nextWriteByte,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + length,
                )
                nextWriteByte = (nextWriteByte + length) % ring.size
                sourceOffset += length
            }
            retainedSamples = minOf(capacitySamples, retainedSamples + incomingSamples)
        }
        sampleCount = newSampleCount
    }

    fun materialize(output: File, startSample: Int, endSampleExclusive: Int) {
        check(!closed) { "Live PCM store is closed" }
        require(
            startSample >= earliestRetainedSample &&
                endSampleExclusive > startSample &&
                endSampleExclusive <= sampleCount,
        ) {
            "Preview segment [$startSample, $endSampleExclusive) is outside retained " +
                "[$earliestRetainedSample, $sampleCount)"
        }
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) error("Unable to replace preview segment")

        val byteCount = Math.multiplyExact(
            endSampleExclusive - startSample,
            PCM16_BYTES_PER_SAMPLE,
        )
        val retainedStartByte =
            (nextWriteByte - retainedSamples * PCM16_BYTES_PER_SAMPLE + ring.size) % ring.size
        var readByte = (
            retainedStartByte +
                (startSample - earliestRetainedSample) * PCM16_BYTES_PER_SAMPLE
            ) % ring.size
        RandomAccessFile(output, "rw").use { wav ->
            wav.setLength(0L)
            writeWavHeader(wav, byteCount)
            var remaining = byteCount
            while (remaining > 0) {
                val length = minOf(remaining, ring.size - readByte)
                wav.write(ring, readByte, length)
                readByte = (readByte + length) % ring.size
                remaining -= length
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ring.fill(0)
    }

    private fun writeWavHeader(output: RandomAccessFile, dataBytes: Int) {
        val byteRate = sampleRateHz * PCM16_BYTES_PER_SAMPLE
        output.writeBytes("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRateHz)
        output.writeIntLe(byteRate)
        output.writeShortLe(PCM16_BYTES_PER_SAMPLE)
        output.writeShortLe(PCM16_BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeIntLe(dataBytes)
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val PCM16_BITS_PER_SAMPLE = 16
        const val DEFAULT_CAPACITY_SECONDS = 30
    }
}
