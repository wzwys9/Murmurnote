package app.murmurnote.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class Pcm16WavStreamReaderTest {

    @Test
    fun canonicalProbeAcceptsOnlyReadableMono16kPcm16Wav() {
        val canonical = temporaryWav(
            waveFile(
                pcm16Mono16kFormatChunk(),
                pcmDataChunk(shortArrayOf(1, 2, 3)),
            ),
        )
        val wrongSampleRate = temporaryWav(
            waveFile(
                pcmFormatChunk(sampleRate = 44_100),
                pcmDataChunk(shortArrayOf(1, 2, 3)),
            ),
        )
        val disguisedCompressedAudio = File.createTempFile("disguised-audio-", ".wav").apply {
            writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00))
        }

        try {
            assertTrue(Pcm16WavStreamReader.isCanonicalMono16kPcmWav(canonical))
            assertFalse(Pcm16WavStreamReader.isCanonicalMono16kPcmWav(wrongSampleRate))
            assertFalse(Pcm16WavStreamReader.isCanonicalMono16kPcmWav(disguisedCompressedAudio))
        } finally {
            canonical.delete()
            wrongSampleRate.delete()
            disguisedCompressedAudio.delete()
        }
    }

    @Test
    fun parsesExtraChunksOddPaddingAndExactPcmSamples() {
        val file = temporaryWav(
            waveFile(
                chunk("JUNK", byteArrayOf(1, 2, 3)),
                pcm16Mono16kFormatChunk(),
                chunk("LIST", byteArrayOf(9, 8, 7, 6, 5)),
                pcmDataChunk(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)),
            ),
        )

        try {
            Pcm16WavStreamReader(file).use { reader ->
                assertEquals(16_000, reader.sampleRateHz)
                assertEquals(5, reader.sampleCount)

                val frame = reader.readFrame(frameSizeSamples = 8)!!
                assertEquals(5, frame.validSampleCount)
                assertArrayEquals(
                    floatArrayOf(-1.0f, -1.0f / 32_768.0f, 0.0f, 1.0f / 32_768.0f, 32_767.0f / 32_768.0f, 0.0f, 0.0f, 0.0f),
                    frame.samples,
                    0.0f,
                )
                assertNull(reader.readFrame(frameSizeSamples = 8))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun streamsIntoOneReusableFrameAndZeroPadsTheTailOnce() {
        val source = ShortArray(513) { index -> (index - 256).toShort() }
        val file = temporaryWav(waveFile(pcm16Mono16kFormatChunk(), pcmDataChunk(source)))

        try {
            Pcm16WavStreamReader(file).use { reader ->
                val reusableFrame = FloatArray(512)

                assertEquals(512, reader.readFrameInto(reusableFrame))
                assertEquals(source[0] / 32_768.0f, reusableFrame.first(), 0.0f)
                assertEquals(source[511] / 32_768.0f, reusableFrame.last(), 0.0f)

                assertEquals(1, reader.readFrameInto(reusableFrame))
                assertEquals(source[512] / 32_768.0f, reusableFrame[0], 0.0f)
                assertTrue(reusableFrame.drop(1).all { it == 0.0f })
                assertNull(reader.readFrameInto(reusableFrame))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun seeksDirectlyToAnExactSampleWithoutReadingThePrefix() {
        val source = ShortArray(1_024) { index -> index.toShort() }
        val file = temporaryWav(waveFile(pcm16Mono16kFormatChunk(), pcmDataChunk(source)))

        try {
            Pcm16WavStreamReader(file).use { reader ->
                reader.seekToSample(510)
                val frame = reader.readFrame(frameSizeSamples = 4)!!

                assertEquals(4, frame.validSampleCount)
                assertArrayEquals(
                    floatArrayOf(510f, 511f, 512f, 513f).map { it / 32_768.0f }.toFloatArray(),
                    frame.samples,
                    0.0f,
                )

                reader.seekToSample(source.size)
                assertNull(reader.readFrame(frameSizeSamples = 4))
                assertThrows(IllegalArgumentException::class.java) {
                    reader.seekToSample(source.size + 1)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun boundedFrameReadNeverConsumesSamplesPastTheRequestedWindow() {
        val source = ShortArray(10) { index -> index.toShort() }
        val file = temporaryWav(waveFile(pcm16Mono16kFormatChunk(), pcmDataChunk(source)))

        try {
            Pcm16WavStreamReader(file).use { reader ->
                val frame = FloatArray(4)

                assertEquals(2, reader.readFrameInto(frame, maxSampleCount = 2))
                assertArrayEquals(
                    floatArrayOf(0f, 1f / 32_768.0f, 0f, 0f),
                    frame,
                    0.0f,
                )

                assertEquals(4, reader.readFrameInto(frame))
                assertArrayEquals(
                    floatArrayOf(2f, 3f, 4f, 5f).map { it / 32_768.0f }.toFloatArray(),
                    frame,
                    0.0f,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun boundedVadWindowMapsLocalRangesBackWithoutDroppingTailSamples() {
        val source = ShortArray(1_024) { index -> index.toShort() }
        val file = temporaryWav(waveFile(pcm16Mono16kFormatChunk(), pcmDataChunk(source)))
        val session = CapturingVadSession(
            flushedRanges = listOf(NeuralVadSegmentPlanner.SpeechRange(2, 9)),
        )

        try {
            val result = PcmVadWindowRunner.detect(
                file = file,
                startSample = 510,
                endSampleExclusive = 520,
                frameSizeSamples = 4,
                sessionFactory = { session },
            )

            assertEquals(1_024, result.recordingSampleCount)
            assertEquals(510, result.analyzedStartSample)
            assertEquals(520, result.analyzedEndSampleExclusive)
            assertEquals(
                listOf(NeuralVadSegmentPlanner.SpeechRange(512, 519)),
                result.speechRanges,
            )
            assertEquals(3, session.acceptedFrames.size)
            assertArrayEquals(
                floatArrayOf(510f, 511f, 512f, 513f).map { it / 32_768.0f }.toFloatArray(),
                session.acceptedFrames[0],
                0.0f,
            )
            assertArrayEquals(
                floatArrayOf(518f / 32_768.0f, 519f / 32_768.0f, 0f, 0f),
                session.acceptedFrames[2],
                0.0f,
            )
            assertTrue(session.closed)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTruncatedChunksExplicitly() {
        val valid = waveFile(
            pcm16Mono16kFormatChunk(),
            pcmDataChunk(shortArrayOf(1, 2, 3)),
        )
        val file = temporaryWav(valid.copyOf(valid.size - 1))

        try {
            val failure = assertThrows(IOException::class.java) {
                Pcm16WavStreamReader(file)
            }
            assertTrue(failure.message.orEmpty().contains("truncated", ignoreCase = true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsAnythingOtherThanPcmMono16Bit16k() {
        val file = temporaryWav(
            waveFile(
                pcmFormatChunk(sampleRate = 44_100),
                pcmDataChunk(shortArrayOf(1, 2)),
            ),
        )

        try {
            val failure = assertThrows(IOException::class.java) {
                Pcm16WavStreamReader(file)
            }
            assertTrue(failure.message.orEmpty().contains("16000"))
        } finally {
            file.delete()
        }
    }

    private fun temporaryWav(bytes: ByteArray): File =
        File.createTempFile("pcm16-stream-reader-", ".wav").apply { writeBytes(bytes) }

    private fun waveFile(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeAscii("WAVE")
            chunks.forEach { chunk -> write(chunk) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeLittleEndianInt(body.size)
            write(body)
        }.toByteArray()
    }

    private fun pcm16Mono16kFormatChunk(): ByteArray = pcmFormatChunk(sampleRate = 16_000)

    private fun pcmFormatChunk(sampleRate: Int): ByteArray = chunk(
        "fmt ",
        ByteArrayOutputStream().apply {
            writeLittleEndianShort(1)
            writeLittleEndianShort(1)
            writeLittleEndianInt(sampleRate)
            writeLittleEndianInt(sampleRate * 2)
            writeLittleEndianShort(2)
            writeLittleEndianShort(16)
        }.toByteArray(),
    )

    private fun pcmDataChunk(samples: ShortArray): ByteArray = chunk(
        "data",
        ByteArrayOutputStream().apply {
            samples.forEach { writeLittleEndianShort(it.toInt()) }
        }.toByteArray(),
    )

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            require(id.length == 4)
            writeAscii(id)
            writeLittleEndianInt(payload.size)
            write(payload)
            if (payload.size % 2 != 0) write(0)
        }.toByteArray()

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private class CapturingVadSession(
        private val flushedRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
    ) : StreamingVadSession {
        val acceptedFrames = mutableListOf<FloatArray>()
        var closed = false
            private set

        override val isSpeechDetected: Boolean = false

        override fun acceptFrame(
            samples: FloatArray,
        ): List<NeuralVadSegmentPlanner.SpeechRange> {
            acceptedFrames += samples.copyOf()
            return emptyList()
        }

        override fun flush(): List<NeuralVadSegmentPlanner.SpeechRange> = flushedRanges

        override fun close() {
            closed = true
        }
    }
}
