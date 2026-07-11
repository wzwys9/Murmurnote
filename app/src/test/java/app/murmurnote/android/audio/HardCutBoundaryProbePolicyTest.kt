package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardCutBoundaryProbePolicyTest {

    @Test
    fun productionPresetFreezesTheBoundedSecondaryProbeContract() {
        val preset = HardCutBoundaryProbePolicy.PRESET

        assertEquals("hard_cut_probe_p065_ms200_win5000_warm500_look500_guard200", preset.version)
        assertEquals(0.65f, preset.threshold, 0.0f)
        assertEquals(200, preset.minSilenceMs)
        assertEquals(5_000, preset.searchWindowMs)
        assertEquals(500, preset.warmupMs)
        assertEquals(500, preset.lookaheadMs)
        assertEquals(200, preset.endGuardMs)

        val vadPreset = HardCutBoundaryProbePolicy.neuralVadPreset()
        assertEquals(0.65f, vadPreset.threshold, 0.0f)
        assertEquals(200, vadPreset.minSilenceMs)
        assertEquals(0, vadPreset.prePaddingMs)
        assertEquals(0, vadPreset.postPaddingMs)
    }

    @Test
    fun windowReadsOnlyFiveSecondsPlusBoundedContext() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )

        assertEquals(19_500, window.analysisStartSample)
        assertEquals(25_500, window.analysisEndSampleExclusive)
        assertEquals(20_000, window.searchStartSample)
        assertEquals(24_800, window.searchEndSampleExclusive)
        assertEquals(6_000, window.analysisEndSampleExclusive - window.analysisStartSample)
    }

    @Test
    fun longestSpeechBoundedPauseWinsInsideSearchWindow() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )
        val speechRanges = listOf(
            NeuralVadSegmentPlanner.SpeechRange(19_500, 21_100),
            NeuralVadSegmentPlanner.SpeechRange(21_300, 23_000),
            NeuralVadSegmentPlanner.SpeechRange(23_400, 24_500),
            NeuralVadSegmentPlanner.SpeechRange(24_700, 25_500),
        )

        assertEquals(
            23_200,
            HardCutBoundaryProbePolicy.selectCutSample(window, speechRanges),
        )
    }

    @Test
    fun equallyLongPausesPreferTheOneClosestToHardLimit() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )
        val speechRanges = listOf(
            NeuralVadSegmentPlanner.SpeechRange(19_500, 21_000),
            NeuralVadSegmentPlanner.SpeechRange(21_300, 23_500),
            NeuralVadSegmentPlanner.SpeechRange(23_800, 25_500),
        )

        assertEquals(
            23_650,
            HardCutBoundaryProbePolicy.selectCutSample(window, speechRanges),
        )
    }

    @Test
    fun unboundedOrSubMinimumSilenceNeverBecomesACut() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )

        assertNull(
            HardCutBoundaryProbePolicy.selectCutSample(
                window,
                listOf(
                    NeuralVadSegmentPlanner.SpeechRange(19_500, 24_000),
                    NeuralVadSegmentPlanner.SpeechRange(24_150, 24_300),
                ),
            ),
        )
        assertNull(
            HardCutBoundaryProbePolicy.selectCutSample(
                window,
                listOf(NeuralVadSegmentPlanner.SpeechRange(19_500, 23_000)),
            ),
        )
    }
}
