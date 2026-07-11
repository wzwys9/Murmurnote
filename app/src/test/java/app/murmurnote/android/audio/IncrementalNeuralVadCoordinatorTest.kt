package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalNeuralVadCoordinatorTest {

    @Test
    fun naturalPauseIsNotPublishedUntilFutureSpeechCanNoLongerMergeWithIt() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val session = ScriptedVadSession(
            emissions = mapOf(
                2_300 to listOf(NeuralVadSegmentPlanner.SpeechRange(1_000, 1_600)),
            ),
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = testPreset(),
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 2_500))
        assertTrue(published.isEmpty())

        coordinator.acceptPcm(pcm16Silence(sampleCount = 100))
        assertTrue(published.isEmpty())
        coordinator.acceptPcm(pcm16Silence(sampleCount = 300))

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 500,
                    endSampleExclusive = 2_100,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            published,
        )
    }

    @Test
    fun activeFutureSpeechPreventsPrematurePublicationUntilItsRangeCanMerge() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val session = ScriptedVadSession(
            emissions = mapOf(
                2_300 to listOf(NeuralVadSegmentPlanner.SpeechRange(1_000, 1_600)),
                3_500 to listOf(NeuralVadSegmentPlanner.SpeechRange(2_400, 2_800)),
            ),
            speechDetected = { accepted -> accepted in 2_600 until 3_500 },
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = testPreset().copy(maxSegmentMs = 5_000),
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 2_700))
        assertTrue(published.isEmpty())
        coordinator.acceptPcm(pcm16Silence(sampleCount = 1_500))

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 500,
                    endSampleExclusive = 3_300,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            published,
        )
    }

    @Test
    fun flushPublishesTheLastPaddedSegmentAgainstTheExactUnpaddedSampleCount() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val session = ScriptedVadSession(
            flushEmission = listOf(NeuralVadSegmentPlanner.SpeechRange(1_000, 1_600)),
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = testPreset(),
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 1_850))
        coordinator.finish()

        assertEquals(1_850, coordinator.acceptedSampleCount)
        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 500,
                    endSampleExclusive = 1_850,
                    cutReason = NeuralVadSegmentPlanner.CutReason.END_OF_AUDIO,
                ),
            ),
            published,
        )
        assertEquals(1, session.flushCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun hardLimitUsesTheFrozenOverlapAndNeverExceedsMaximumDuration() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val preset = testPreset().copy(
            prePaddingMs = 0,
            postPaddingMs = 0,
            maxSegmentMs = 1_000,
            hardCutOverlapMs = 200,
        )
        val session = ScriptedVadSession(
            emissions = mapOf(
                2_600 to listOf(NeuralVadSegmentPlanner.SpeechRange(0, 2_500)),
            ),
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = preset,
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 2_800))

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    0,
                    1_000,
                    NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    800,
                    1_800,
                    NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(1_600, 2_500, NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE),
            ),
            published,
        )
        assertEquals(200, published[0].endSampleExclusive - published[1].startSample)
        assertEquals(200, published[1].endSampleExclusive - published[2].startSample)
        assertTrue(published.all { it.endSampleExclusive - it.startSample <= 1_000 })
    }

    @Test
    fun continuingSpeechWaitsForProbeLookaheadAndPublishesARefinedBoundary() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val requestedDeadlines = mutableListOf<Int>()
        val preset = testPreset().copy(
            prePaddingMs = 0,
            postPaddingMs = 0,
            maxSegmentMs = 1_000,
            hardCutOverlapMs = 200,
        )
        val session = ScriptedVadSession(
            emissions = mapOf(
                1_000 to listOf(NeuralVadSegmentPlanner.SpeechRange(0, 1_000)),
                1_200 to listOf(NeuralVadSegmentPlanner.SpeechRange(1_000, 1_200)),
            ),
            speechDetected = { accepted -> accepted >= 1_000 },
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = preset,
            hardCutRefinementLookaheadMs = 200,
            hardCutBoundaryRefiner = { request ->
                requestedDeadlines += request.hardLimitEndSample
                request.hardLimitEndSample - 100
            },
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 1_000))
        assertTrue(published.isEmpty())
        assertTrue(requestedDeadlines.isEmpty())
        coordinator.acceptPcm(pcm16Silence(sampleCount = 200))

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 0,
                    endSampleExclusive = 900,
                    cutReason = NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT,
                ),
            ),
            published,
        )
        assertEquals(listOf(1_000), requestedDeadlines)

        coordinator.acceptPcm(pcm16Silence(sampleCount = 300))
        assertEquals(listOf(1_000), requestedDeadlines)
    }

    @Test
    fun failedProbeWaitsForLookaheadThenPublishesTheFallbackHardLimit() {
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val requestedDeadlines = mutableListOf<Int>()
        val preset = testPreset().copy(
            prePaddingMs = 0,
            postPaddingMs = 0,
            maxSegmentMs = 1_000,
            hardCutOverlapMs = 200,
        )
        val session = ScriptedVadSession(
            emissions = mapOf(
                1_000 to listOf(NeuralVadSegmentPlanner.SpeechRange(0, 1_000)),
                1_200 to listOf(NeuralVadSegmentPlanner.SpeechRange(1_000, 1_200)),
            ),
            speechDetected = { accepted -> accepted >= 1_000 },
        )
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = preset,
            hardCutRefinementLookaheadMs = 200,
            hardCutBoundaryRefiner = { request ->
                requestedDeadlines += request.hardLimitEndSample
                null
            },
            onSegment = published::add,
        )

        coordinator.acceptPcm(pcm16Silence(sampleCount = 1_000))
        assertTrue(published.isEmpty())
        coordinator.acceptPcm(pcm16Silence(sampleCount = 200))

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 0,
                    endSampleExclusive = 1_000,
                    cutReason = NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
            ),
            published,
        )
        assertEquals(listOf(1_000), requestedDeadlines)
    }

    @Test
    fun neuralFailurePropagatesWithoutPublishingAnEnergyFallback() {
        val session = object : StreamingVadSession {
            override val isSpeechDetected: Boolean = false

            override fun acceptFrame(samples: FloatArray): List<NeuralVadSegmentPlanner.SpeechRange> =
                error("native inference failed")

            override fun flush(): List<NeuralVadSegmentPlanner.SpeechRange> = emptyList()
            override fun close() = Unit
        }
        val published = mutableListOf<NeuralVadSegmentPlanner.Segment>()
        val coordinator = IncrementalNeuralVadCoordinator(
            session = session,
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = testPreset(),
            onSegment = published::add,
        )

        assertThrows(IllegalStateException::class.java) {
            coordinator.acceptPcm(pcm16Silence(sampleCount = 100))
        }
        assertTrue(published.isEmpty())
    }

    @Test
    fun rejectsOddPcm16ChunksInsteadOfSilentlyChangingTheTimeline() {
        val coordinator = IncrementalNeuralVadCoordinator(
            session = ScriptedVadSession(),
            sampleRateHz = 1_000,
            frameSizeSamples = 100,
            preset = testPreset(),
            onSegment = {},
        )

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.acceptPcm(byteArrayOf(1, 2, 3))
        }
    }

    private class ScriptedVadSession(
        private val emissions: Map<Int, List<NeuralVadSegmentPlanner.SpeechRange>> = emptyMap(),
        private val flushEmission: List<NeuralVadSegmentPlanner.SpeechRange> = emptyList(),
        private val speechDetected: (Int) -> Boolean = { false },
    ) : StreamingVadSession {
        private var acceptedSamples = 0
        var flushCalls = 0
            private set
        var closeCalls = 0
            private set

        override val isSpeechDetected: Boolean
            get() = speechDetected(acceptedSamples)

        override fun acceptFrame(samples: FloatArray): List<NeuralVadSegmentPlanner.SpeechRange> {
            acceptedSamples += samples.size
            return emissions[acceptedSamples].orEmpty()
        }

        override fun flush(): List<NeuralVadSegmentPlanner.SpeechRange> {
            flushCalls += 1
            return flushEmission
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private fun testPreset() = NeuralVadSegmentPlanner.Preset(
        version = "test",
        threshold = 0.5f,
        minSpeechMs = 250,
        minSilenceMs = 700,
        prePaddingMs = 500,
        postPaddingMs = 500,
        maxSegmentMs = 2_500,
        hardCutOverlapMs = 500,
        minFinalSegmentMs = 200,
    )

    private fun pcm16Silence(sampleCount: Int): ByteArray = ByteArray(sampleCount * 2)
}
