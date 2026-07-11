package app.murmurnote.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class LivePcmSegmentStoreTest {

    @Test
    fun materializesExactlyTheRequestedSampleRangeAcrossRingWrap() {
        val directory = Files.createTempDirectory("live-vad-ring-").toFile()
        val samples = shortArrayOf(-32_768, -1, 0, 1, 2, 3, 4, 32_767)
        val store = LivePcmSegmentStore(
            outputDirectory = directory,
            sampleRateHz = 16_000,
            capacitySamples = 6,
        )
        try {
            store.append(samples.copyOfRange(0, 4).toPcm16Le())
            store.append(samples.copyOfRange(4, 8).toPcm16Le())

            assertEquals(2, store.earliestRetainedSample)
            val output = directory.resolve("segment.wav")
            store.materialize(output, startSample = 2, endSampleExclusive = 7)

            Pcm16WavStreamReader(output).use { reader ->
                assertEquals(5, reader.sampleCount)
                val frame = reader.readFrame(5)!!
                assertArrayEquals(
                    floatArrayOf(0f, 1f / 32_768f, 2f / 32_768f, 3f / 32_768f, 4f / 32_768f),
                    frame.samples,
                    0f,
                )
            }
        } finally {
            store.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesToMaterializeSamplesEvictedFromTheBoundedRing() {
        val directory = Files.createTempDirectory("live-vad-ring-bounds-").toFile()
        try {
            LivePcmSegmentStore(
                outputDirectory = directory,
                sampleRateHz = 16_000,
                capacitySamples = 2,
            ).use { store ->
                store.append(shortArrayOf(1, 2, 3).toPcm16Le())
                assertEquals(1, store.earliestRetainedSample)
                assertThrows(IllegalArgumentException::class.java) {
                    store.materialize(directory.resolve("bad.wav"), 0, 2)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ShortArray.toPcm16Le(): ByteArray = ByteArray(size * 2).also { bytes ->
        forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
    }
}
