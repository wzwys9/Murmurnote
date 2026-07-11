package app.murmurnote.android.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bounded-memory reader for the exact WAV format consumed by the on-device VAD.
 *
 * RIFF chunks are scanned instead of assuming a 44-byte header. Audio is exposed one floating
 * point frame at a time; only the final frame is zero-padded and [Frame.validSampleCount] keeps
 * the real input length available to callers.
 */
class Pcm16WavStreamReader(file: File) : Closeable {

    data class Frame(
        val samples: FloatArray,
        val validSampleCount: Int,
    )

    val sampleRateHz: Int
    val sampleCount: Int

    private val input = RandomAccessFile(file, "r")
    private val dataOffset: Long
    private var samplesRead = 0
    private var pcmByteBuffer = ByteArray(0)

    init {
        val metadata = try {
            parseMetadata()
        } catch (failure: Throwable) {
            runCatching { input.close() }
            throw failure
        }
        sampleRateHz = metadata.sampleRateHz
        sampleCount = metadata.sampleCount
        dataOffset = metadata.dataOffset
        input.seek(dataOffset)
    }

    /**
     * Reads the next PCM frame. Full frames contain only real samples; the one final partial frame
     * is zero-padded. A subsequent call returns null.
     */
    @Throws(IOException::class)
    fun readFrame(frameSizeSamples: Int): Frame? {
        validateFrameSize(frameSizeSamples)
        val samples = FloatArray(frameSizeSamples)
        val validSampleCount = readFrameInto(samples) ?: return null
        return Frame(samples = samples, validSampleCount = validSampleCount)
    }

    /**
     * Bounded-allocation variant used by VAD: the same caller-owned frame can be reused for the
     * entire recording. [maxSampleCount] lets a bounded analysis window stop inside a frame
     * without consuming PCM that belongs to the following window. Returns the number of real
     * samples, or null after the PCM data is drained.
     */
    @Throws(IOException::class)
    fun readFrameInto(
        destination: FloatArray,
        maxSampleCount: Int = destination.size,
    ): Int? {
        validateFrameSize(destination.size)
        require(maxSampleCount in 1..destination.size) {
            "Maximum sample count must fit inside the destination frame"
        }
        if (samplesRead == sampleCount) return null

        val validSampleCount = minOf(maxSampleCount, sampleCount - samplesRead)
        val byteCount = validSampleCount * BYTES_PER_SAMPLE
        if (pcmByteBuffer.size < byteCount) pcmByteBuffer = ByteArray(byteCount)
        try {
            input.readFully(pcmByteBuffer, 0, byteCount)
        } catch (failure: IOException) {
            throw IOException("Truncated PCM data while reading sample $samplesRead", failure)
        }

        repeat(validSampleCount) { index ->
            val byteOffset = index * BYTES_PER_SAMPLE
            val unsigned =
                (pcmByteBuffer[byteOffset].toInt() and 0xff) or
                    ((pcmByteBuffer[byteOffset + 1].toInt() and 0xff) shl 8)
            val signed = if (unsigned >= SIGN_BIT) unsigned - UNSIGNED_SHORT_RANGE else unsigned
            destination[index] = signed / PCM_SCALE
        }
        if (validSampleCount < destination.size) {
            destination.fill(0.0f, fromIndex = validSampleCount, toIndex = destination.size)
        }
        samplesRead += validSampleCount
        return validSampleCount
    }

    /** Repositions the bounded reader without decoding or allocating the skipped PCM prefix. */
    internal fun seekToSample(sampleIndex: Int) {
        require(sampleIndex in 0..sampleCount) { "Sample seek is outside the WAV data" }
        input.seek(dataOffset + sampleIndex.toLong() * BYTES_PER_SAMPLE)
        samplesRead = sampleIndex
    }

    internal val nextSampleIndex: Int
        get() = samplesRead

    override fun close() {
        input.close()
    }

    @Throws(IOException::class)
    private fun parseMetadata(): Metadata {
        if (input.length() < RIFF_HEADER_BYTES) {
            throw IOException("Truncated RIFF/WAVE header")
        }
        if (readFourCc() != RIFF_ID) throw IOException("Invalid WAV: missing RIFF header")

        val riffPayloadBytes = readUnsignedIntLittleEndian()
        val riffEnd = RIFF_PREFIX_BYTES + riffPayloadBytes
        if (riffPayloadBytes < WAVE_ID_BYTES || riffEnd < RIFF_HEADER_BYTES) {
            throw IOException("Invalid WAV: RIFF payload is too short")
        }
        if (riffEnd > input.length()) {
            throw IOException(
                "Truncated RIFF payload: declared end=$riffEnd, file length=${input.length()}",
            )
        }
        if (readFourCc() != WAVE_ID) throw IOException("Invalid WAV: missing WAVE identifier")

        var format: PcmFormat? = null
        var dataOffset: Long? = null
        var dataSizeBytes: Long? = null

        while (input.filePointer < riffEnd) {
            ensureChunkBytesAvailable(CHUNK_HEADER_BYTES, riffEnd, "chunk header")
            val chunkId = readFourCc()
            val chunkSize = readUnsignedIntLittleEndian()
            val chunkDataOffset = input.filePointer
            val chunkEnd = chunkDataOffset + chunkSize
            if (chunkEnd < chunkDataOffset || chunkEnd > riffEnd) {
                throw IOException("Truncated WAV chunk '$chunkId': declared size=$chunkSize")
            }

            when (chunkId) {
                FORMAT_ID -> {
                    if (format != null) throw IOException("Invalid WAV: duplicate fmt chunk")
                    format = readPcmFormat(chunkSize, chunkEnd)
                }

                DATA_ID -> {
                    if (dataOffset != null) throw IOException("Invalid WAV: multiple data chunks")
                    dataOffset = chunkDataOffset
                    dataSizeBytes = chunkSize
                }
            }

            val paddedChunkEnd = chunkEnd + (chunkSize and 1L)
            if (paddedChunkEnd > riffEnd) {
                throw IOException("Truncated WAV chunk '$chunkId': missing odd-byte padding")
            }
            input.seek(paddedChunkEnd)
        }

        val pcmFormat = format ?: throw IOException("Invalid WAV: missing fmt chunk")
        validatePcmFormat(pcmFormat)
        val pcmOffset = dataOffset ?: throw IOException("Invalid WAV: missing data chunk")
        val pcmByteCount = dataSizeBytes ?: throw IOException("Invalid WAV: missing data size")
        if (pcmByteCount % BYTES_PER_SAMPLE != 0L) {
            throw IOException("Invalid WAV: PCM16 data has an odd byte count ($pcmByteCount)")
        }
        val sampleCount = pcmByteCount / BYTES_PER_SAMPLE
        if (sampleCount > Int.MAX_VALUE) {
            throw IOException("WAV is too large for sample-indexed segmentation: $sampleCount samples")
        }

        return Metadata(
            dataOffset = pcmOffset,
            sampleRateHz = pcmFormat.sampleRateHz,
            sampleCount = sampleCount.toInt(),
        )
    }

    @Throws(IOException::class)
    private fun readPcmFormat(chunkSize: Long, chunkEnd: Long): PcmFormat {
        if (chunkSize < PCM_FORMAT_BYTES) {
            throw IOException("Invalid WAV: fmt chunk is shorter than $PCM_FORMAT_BYTES bytes")
        }
        ensureChunkBytesAvailable(PCM_FORMAT_BYTES, chunkEnd, "PCM format")
        return PcmFormat(
            audioFormat = readUnsignedShortLittleEndian(),
            channelCount = readUnsignedShortLittleEndian(),
            sampleRateHz = readUnsignedIntLittleEndian().toIntExact("sample rate"),
            byteRate = readUnsignedIntLittleEndian().toIntExact("byte rate"),
            blockAlign = readUnsignedShortLittleEndian(),
            bitsPerSample = readUnsignedShortLittleEndian(),
        )
    }

    @Throws(IOException::class)
    private fun validatePcmFormat(format: PcmFormat) {
        if (format.audioFormat != PCM_FORMAT_CODE) {
            throw IOException("Unsupported WAV format ${format.audioFormat}; PCM (1) is required")
        }
        if (format.channelCount != CHANNEL_COUNT) {
            throw IOException("Unsupported WAV channels ${format.channelCount}; mono is required")
        }
        if (format.sampleRateHz != REQUIRED_SAMPLE_RATE_HZ) {
            throw IOException(
                "Unsupported WAV sample rate ${format.sampleRateHz}; 16000 Hz is required",
            )
        }
        if (format.bitsPerSample != BITS_PER_SAMPLE) {
            throw IOException(
                "Unsupported WAV bit depth ${format.bitsPerSample}; 16-bit PCM is required",
            )
        }
        if (format.blockAlign != BYTES_PER_SAMPLE) {
            throw IOException("Invalid WAV block alignment ${format.blockAlign}; expected 2")
        }
        if (format.byteRate != REQUIRED_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE) {
            throw IOException("Invalid WAV byte rate ${format.byteRate}; expected 32000")
        }
    }

    private fun validateFrameSize(frameSizeSamples: Int) {
        require(frameSizeSamples in 1..(Int.MAX_VALUE / BYTES_PER_SAMPLE)) {
            "Frame size is outside the supported range"
        }
    }

    @Throws(IOException::class)
    private fun ensureChunkBytesAvailable(count: Long, boundary: Long, description: String) {
        if (count < 0L || input.filePointer + count > boundary) {
            throw IOException("Truncated WAV $description")
        }
    }

    @Throws(IOException::class)
    private fun readFourCc(): String {
        val bytes = ByteArray(FOUR_CC_BYTES)
        try {
            input.readFully(bytes)
        } catch (failure: IOException) {
            throw IOException("Truncated WAV chunk identifier", failure)
        }
        return bytes.toString(Charsets.US_ASCII)
    }

    @Throws(IOException::class)
    private fun readUnsignedShortLittleEndian(): Int {
        val low = input.read()
        val high = input.read()
        if (low < 0 || high < 0) throw IOException("Truncated WAV integer field")
        return low or (high shl 8)
    }

    @Throws(IOException::class)
    private fun readUnsignedIntLittleEndian(): Long {
        val low = readUnsignedShortLittleEndian().toLong()
        val high = readUnsignedShortLittleEndian().toLong()
        return low or (high shl 16)
    }

    @Throws(IOException::class)
    private fun Long.toIntExact(description: String): Int {
        if (this !in 0..Int.MAX_VALUE.toLong()) {
            throw IOException("Invalid WAV $description: $this")
        }
        return toInt()
    }

    private data class Metadata(
        val dataOffset: Long,
        val sampleRateHz: Int,
        val sampleCount: Int,
    )

    private data class PcmFormat(
        val audioFormat: Int,
        val channelCount: Int,
        val sampleRateHz: Int,
        val byteRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    companion object {
        internal fun isCanonicalMono16kPcmWav(file: File): Boolean = try {
            Pcm16WavStreamReader(file).use { reader -> reader.sampleCount > 0 }
        } catch (_: IOException) {
            false
        }

        private const val RIFF_ID = "RIFF"
        private const val WAVE_ID = "WAVE"
        private const val FORMAT_ID = "fmt "
        private const val DATA_ID = "data"

        private const val FOUR_CC_BYTES = 4
        private const val RIFF_PREFIX_BYTES = 8L
        private const val WAVE_ID_BYTES = 4L
        private const val RIFF_HEADER_BYTES = 12L
        private const val CHUNK_HEADER_BYTES = 8L
        private const val PCM_FORMAT_BYTES = 16L

        private const val PCM_FORMAT_CODE = 1
        private const val CHANNEL_COUNT = 1
        private const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val SIGN_BIT = 32_768
        private const val UNSIGNED_SHORT_RANGE = 65_536
        private const val PCM_SCALE = 32_768.0f
    }
}
