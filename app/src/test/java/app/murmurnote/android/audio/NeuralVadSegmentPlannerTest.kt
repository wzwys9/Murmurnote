package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NeuralVadSegmentPlannerTest {

    @Test
    fun productionPresetHasTheFrozenSileroV5Values() {
        val preset = NeuralVadSegmentPlanner.PRESET

        assertEquals(
            "silero_v5_0_p050_s250_ms450_pre250_post250_max25000_ov500_min200",
            preset.version,
        )
        assertEquals(0.5f, preset.threshold, 0.0f)
        assertEquals(250, preset.minSpeechMs)
        assertEquals(450, preset.minSilenceMs)
        assertEquals(250, preset.prePaddingMs)
        assertEquals(250, preset.postPaddingMs)
        assertEquals(25_000, preset.maxSegmentMs)
        assertEquals(500, preset.hardCutOverlapMs)
        assertEquals(200, preset.minFinalSegmentMs)
    }

    @Test
    fun emptySpeechProducesNoSegments() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 16_000,
            sampleRateHz = 16_000,
            speechRanges = emptyList(),
        )

        assertEquals(emptyList<NeuralVadSegmentPlanner.Segment>(), segments)
    }

    @Test
    fun paddingIsClampedToAudioBoundaries() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 10_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(100, 300),
                NeuralVadSegmentPlanner.SpeechRange(9_700, 10_000),
            ),
        )

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 0,
                    endSampleExclusive = 550,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 9_450,
                    endSampleExclusive = 10_000,
                    cutReason = NeuralVadSegmentPlanner.CutReason.END_OF_AUDIO,
                ),
            ),
            segments,
        )
    }

    @Test
    fun paddingThatTouchesOrOverlapsMergesSpeechRanges() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 10_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(1_000, 1_200),
                NeuralVadSegmentPlanner.SpeechRange(1_700, 1_900),
            ),
        )

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 750,
                    endSampleExclusive = 2_150,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            segments,
        )
    }

    @Test
    fun fiveHundredAndFiftyMillisecondPauseProducesSeparateSegments() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 4_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(1_000, 1_500),
                NeuralVadSegmentPlanner.SpeechRange(2_050, 2_550),
            ),
        )

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 750,
                    endSampleExclusive = 1_750,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 1_800,
                    endSampleExclusive = 2_800,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            segments,
        )
    }

    @Test
    fun finalSegmentShorterThanTwoHundredMillisecondsIsDropped() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 199,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(50, 150),
            ),
        )

        assertEquals(emptyList<NeuralVadSegmentPlanner.Segment>(), segments)
    }

    @Test
    fun longSpeechIsHardCutWithExactlyFiveHundredMillisecondsOverlap() {
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 70_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(500, 59_500),
            ),
        )

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 250,
                    endSampleExclusive = 25_250,
                    cutReason = NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 24_750,
                    endSampleExclusive = 49_750,
                    cutReason = NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 49_250,
                    endSampleExclusive = 59_750,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            segments,
        )
        assertEquals(500, segments[0].endSampleExclusive - segments[1].startSample)
        assertEquals(500, segments[1].endSampleExclusive - segments[2].startSample)
    }

    @Test
    fun refinedHardCutsMoveLaterDeadlinesWithoutCreatingAudioGaps() {
        val preset = NeuralVadSegmentPlanner.PRESET.copy(
            prePaddingMs = 0,
            postPaddingMs = 0,
        )
        val requestedHardLimits = mutableListOf<Int>()

        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 60_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(0, 60_000),
            ),
            preset = preset,
            hardCutBoundaryRefiner = { request ->
                requestedHardLimits += request.hardLimitEndSample
                request.hardLimitEndSample - 2_000
            },
        )

        assertEquals(listOf(25_000, 47_500), requestedHardLimits)
        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 0,
                    endSampleExclusive = 23_000,
                    cutReason = NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 22_500,
                    endSampleExclusive = 45_500,
                    cutReason = NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 45_000,
                    endSampleExclusive = 60_000,
                    cutReason = NeuralVadSegmentPlanner.CutReason.END_OF_AUDIO,
                ),
            ),
            segments,
        )
        assertEquals(500, segments[0].endSampleExclusive - segments[1].startSample)
        assertEquals(500, segments[1].endSampleExclusive - segments[2].startSample)
    }

    @Test
    fun hardCutMovesEarlierRatherThanLeavingASubMinimumTail() {
        val preset = NeuralVadSegmentPlanner.PRESET.copy(
            prePaddingMs = 0,
            postPaddingMs = 0,
            maxSegmentMs = 3_000,
            hardCutOverlapMs = 100,
            minFinalSegmentMs = 200,
        )

        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = 5_000,
            sampleRateHz = 1_000,
            speechRanges = listOf(
                NeuralVadSegmentPlanner.SpeechRange(1_000, 4_050),
            ),
            preset = preset,
        )

        assertEquals(
            listOf(
                NeuralVadSegmentPlanner.Segment(
                    startSample = 1_000,
                    endSampleExclusive = 3_950,
                    cutReason = NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                ),
                NeuralVadSegmentPlanner.Segment(
                    startSample = 3_850,
                    endSampleExclusive = 4_050,
                    cutReason = NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE,
                ),
            ),
            segments,
        )
    }

    @Test
    fun rejectsSpeechRangesThatAreNotSorted() {
        assertThrows(IllegalArgumentException::class.java) {
            NeuralVadSegmentPlanner.plan(
                sampleCount = 10_000,
                sampleRateHz = 1_000,
                speechRanges = listOf(
                    NeuralVadSegmentPlanner.SpeechRange(2_000, 2_500),
                    NeuralVadSegmentPlanner.SpeechRange(1_000, 1_500),
                ),
            )
        }
    }

    @Test
    fun rejectsOverlappingSpeechRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            NeuralVadSegmentPlanner.plan(
                sampleCount = 10_000,
                sampleRateHz = 1_000,
                speechRanges = listOf(
                    NeuralVadSegmentPlanner.SpeechRange(1_000, 2_000),
                    NeuralVadSegmentPlanner.SpeechRange(1_900, 2_500),
                ),
            )
        }
    }

    @Test
    fun rejectsSpeechRangesOutsideTheAudio() {
        assertThrows(IllegalArgumentException::class.java) {
            NeuralVadSegmentPlanner.plan(
                sampleCount = 10_000,
                sampleRateHz = 1_000,
                speechRanges = listOf(
                    NeuralVadSegmentPlanner.SpeechRange(9_500, 10_001),
                ),
            )
        }
    }
}
