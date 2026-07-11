package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class LiveHardCutBoundaryRefinerTest {

    @Test
    fun probesTheRetainedPcmWithFiveThenTenSecondWindows() {
        val directory = Files.createTempDirectory("live-hard-cut-").toFile()
        val analyzedSampleCounts = mutableListOf<Int>()
        val store = LivePcmSegmentStore(
            outputDirectory = directory,
            sampleRateHz = 16_000,
            capacitySamples = 30 * 16_000,
        )
        try {
            store.append(ByteArray(30 * 16_000 * 2))
            val refiner = LiveHardCutBoundaryRefiner(
                store = store,
                sampleRateHz = 16_000,
                frameSizeSamples = 160,
                sessionFactory = { _ ->
                    RecordingProbeSession { acceptedSamples ->
                        analyzedSampleCounts += acceptedSamples
                        if (acceptedSamples == 6 * 16_000) {
                            listOf(NeuralVadSegmentPlanner.SpeechRange(0, 6 * 16_000))
                        } else {
                            listOf(
                                NeuralVadSegmentPlanner.SpeechRange(0, 56_000),
                                NeuralVadSegmentPlanner.SpeechRange(62_400, acceptedSamples),
                            )
                        }
                    }
                },
            )

            val refinement = refiner.refine(
                NeuralVadSegmentPlanner.HardCutRequest(
                    segmentStartSample = 0,
                    hardLimitEndSample = 25 * 16_000,
                ),
            )

            assertEquals(listOf(6 * 16_000, 11 * 16_000), analyzedSampleCounts)
            assertEquals(291_200, refinement.cutSample)
            assertEquals(2, refinement.attempts.size)
            assertFalse(directory.resolve(".hard_cut_probe.wav").exists())
        } finally {
            store.close()
            directory.deleteRecursively()
        }
    }

    private class RecordingProbeSession(
        private val onFlush: (Int) -> List<NeuralVadSegmentPlanner.SpeechRange>,
    ) : StreamingVadSession {
        private var acceptedSamples = 0

        override val isSpeechDetected: Boolean = false

        override fun acceptFrame(
            samples: FloatArray,
        ): List<NeuralVadSegmentPlanner.SpeechRange> {
            acceptedSamples += samples.size
            return emptyList()
        }

        override fun flush(): List<NeuralVadSegmentPlanner.SpeechRange> = onFlush(acceptedSamples)

        override fun close() = Unit
    }
}
