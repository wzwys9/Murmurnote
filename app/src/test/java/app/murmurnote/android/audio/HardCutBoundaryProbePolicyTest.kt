package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardCutBoundaryProbePolicyTest {

    @Test
    fun productionPresetFreezesTheBoundedSecondaryProbeContract() {
        val preset = HardCutBoundaryProbePolicy.PRESET

        assertEquals(
            "hard_cut_probe_p065_ms200_win5000_fallback_p075_ms140_win10000_" +
                "rescue_p082_ms120_nearest_warm500_look500_guard200",
            preset.version,
        )
        assertEquals(0.65f, preset.threshold, 0.0f)
        assertEquals(200, preset.minSilenceMs)
        assertEquals(0.75f, preset.fallbackThreshold, 0.0f)
        assertEquals(140, preset.fallbackMinSilenceMs)
        assertEquals(0.82f, preset.rescueThreshold, 0.0f)
        assertEquals(120, preset.rescueMinSilenceMs)
        assertEquals(5_000, preset.primarySearchWindowMs)
        assertEquals(10_000, preset.fallbackSearchWindowMs)
        assertEquals(500, preset.warmupMs)
        assertEquals(500, preset.lookaheadMs)
        assertEquals(200, preset.endGuardMs)

        val vadPreset = HardCutBoundaryProbePolicy.neuralVadPreset()
        assertEquals(0.65f, vadPreset.threshold, 0.0f)
        assertEquals(200, vadPreset.minSilenceMs)
        assertEquals(0, vadPreset.prePaddingMs)
        assertEquals(0, vadPreset.postPaddingMs)

        val fallbackWindow = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
            searchWindowMs = preset.fallbackSearchWindowMs,
        )
        val fallbackVadPreset = HardCutBoundaryProbePolicy.neuralVadPreset(fallbackWindow)
        assertEquals(0.75f, fallbackVadPreset.threshold, 0.0f)
        assertEquals(140, fallbackVadPreset.minSilenceMs)

        val rescueWindow = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
            searchWindowMs = preset.fallbackSearchWindowMs,
            stage = HardCutBoundaryProbePolicy.ProbeStage.RESCUE,
        )
        val rescueVadPreset = HardCutBoundaryProbePolicy.neuralVadPreset(rescueWindow)
        assertEquals(0.82f, rescueVadPreset.threshold, 0.0f)
        assertEquals(120, rescueVadPreset.minSilenceMs)
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
    fun fallbackWindowReadsTenSecondsPlusBoundedContext() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
            searchWindowMs = HardCutBoundaryProbePolicy.PRESET.fallbackSearchWindowMs,
        )

        assertEquals(14_500, window.analysisStartSample)
        assertEquals(25_500, window.analysisEndSampleExclusive)
        assertEquals(15_000, window.searchStartSample)
        assertEquals(24_800, window.searchEndSampleExclusive)
        assertEquals(10_000, window.searchWindowMs)
    }

    @Test
    fun pauseClosestToHardLimitWinsOnceItMeetsTheSafetyThreshold() {
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
            24_600,
            HardCutBoundaryProbePolicy.selectCut(window, speechRanges).cutSample,
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
            HardCutBoundaryProbePolicy.selectCut(window, speechRanges).cutSample,
        )
    }

    @Test
    fun failedSelectionExplainsWhyNoCutWasSafe() {
        val window = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )

        val tooShort = HardCutBoundaryProbePolicy.selectCut(
            window,
            listOf(
                NeuralVadSegmentPlanner.SpeechRange(19_500, 24_000),
                NeuralVadSegmentPlanner.SpeechRange(24_150, 24_300),
            ),
        )
        assertNull(tooShort.cutSample)
        assertEquals(HardCutBoundaryProbePolicy.Outcome.PAUSES_TOO_SHORT, tooShort.outcome)
        assertEquals(150, tooShort.longestBoundedPauseSamples)

        val unbounded = HardCutBoundaryProbePolicy.selectCut(
            window,
            listOf(NeuralVadSegmentPlanner.SpeechRange(19_500, 23_000)),
        )
        assertNull(unbounded.cutSample)
        assertEquals(HardCutBoundaryProbePolicy.Outcome.NO_BOUNDED_PAUSE, unbounded.outcome)

        val noSpeech = HardCutBoundaryProbePolicy.selectCut(window, emptyList())
        assertNull(noSpeech.cutSample)
        assertEquals(HardCutBoundaryProbePolicy.Outcome.NO_SPEECH_RANGES, noSpeech.outcome)
    }

    @Test
    fun fallbackProfileAcceptsAShortPauseRejectedByThePrimaryProfile() {
        fun ranges(window: HardCutBoundaryProbePolicy.Window) = listOf(
            NeuralVadSegmentPlanner.SpeechRange(window.analysisStartSample, 23_000),
            NeuralVadSegmentPlanner.SpeechRange(23_148, window.analysisEndSampleExclusive),
        )
        val primaryWindow = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        )
        val fallbackWindow = HardCutBoundaryProbePolicy.window(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
            searchWindowMs = HardCutBoundaryProbePolicy.PRESET.fallbackSearchWindowMs,
        )

        assertNull(HardCutBoundaryProbePolicy.selectCut(primaryWindow, ranges(primaryWindow)).cutSample)
        assertEquals(
            23_074,
            HardCutBoundaryProbePolicy.selectCut(fallbackWindow, ranges(fallbackWindow)).cutSample,
        )
    }

    @Test
    fun rescueProfileRunsOnlyAfterPrimaryAndFallbackRejectThePause() {
        val analyzedStages = mutableListOf<HardCutBoundaryProbePolicy.ProbeStage>()

        val refinement = HardCutBoundaryProbePolicy.refine(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        ) { window ->
            analyzedStages += window.stage
            listOf(
                NeuralVadSegmentPlanner.SpeechRange(window.analysisStartSample, 23_000),
                NeuralVadSegmentPlanner.SpeechRange(23_130, window.analysisEndSampleExclusive),
            )
        }

        assertEquals(
            listOf(
                HardCutBoundaryProbePolicy.ProbeStage.PRIMARY,
                HardCutBoundaryProbePolicy.ProbeStage.FALLBACK,
                HardCutBoundaryProbePolicy.ProbeStage.RESCUE,
            ),
            analyzedStages,
        )
        assertEquals(23_065, refinement.cutSample)
        assertEquals(3, refinement.attempts.size)
    }

    @Test
    fun failedFiveSecondProbeRetriesTenSecondsBeforeFallingBackToDeadline() {
        val analyzedWindows = mutableListOf<Int>()

        val refinement = HardCutBoundaryProbePolicy.refine(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        ) { window ->
            analyzedWindows += window.searchWindowMs
            if (window.searchWindowMs == 5_000) {
                listOf(NeuralVadSegmentPlanner.SpeechRange(19_500, 25_500))
            } else {
                listOf(
                    NeuralVadSegmentPlanner.SpeechRange(14_500, 18_000),
                    NeuralVadSegmentPlanner.SpeechRange(18_400, 25_500),
                )
            }
        }

        assertEquals(listOf(5_000, 10_000), analyzedWindows)
        assertEquals(18_200, refinement.cutSample)
        assertEquals(2, refinement.attempts.size)
        assertEquals(
            HardCutBoundaryProbePolicy.Outcome.NO_BOUNDED_PAUSE,
            refinement.attempts.first().selection.outcome,
        )
        assertEquals(
            HardCutBoundaryProbePolicy.Outcome.REFINED,
            refinement.attempts.last().selection.outcome,
        )
    }

    @Test
    fun successfulFiveSecondProbeDoesNotRunTheFallbackWindow() {
        var probeCalls = 0

        val refinement = HardCutBoundaryProbePolicy.refine(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        ) { window ->
            probeCalls += 1
            listOf(
                NeuralVadSegmentPlanner.SpeechRange(window.analysisStartSample, 23_000),
                NeuralVadSegmentPlanner.SpeechRange(23_400, window.analysisEndSampleExclusive),
            )
        }

        assertEquals(1, probeCalls)
        assertEquals(23_200, refinement.cutSample)
        assertEquals(5_000, refinement.attempts.single().window.searchWindowMs)
    }

    @Test
    fun allFailedProfilesReturnNoRefinementForThePlannerFallback() {
        val refinement = HardCutBoundaryProbePolicy.refine(
            hardLimitEndSample = 25_000,
            recordingSampleCount = 60_000,
            sampleRateHz = 1_000,
        ) { window ->
            listOf(
                NeuralVadSegmentPlanner.SpeechRange(
                    window.analysisStartSample,
                    window.analysisEndSampleExclusive,
                ),
            )
        }

        assertNull(refinement.cutSample)
        assertEquals(
            listOf(5_000, 10_000, 10_000),
            refinement.attempts.map { it.window.searchWindowMs },
        )
        assertEquals(
            listOf(
                HardCutBoundaryProbePolicy.Outcome.NO_BOUNDED_PAUSE,
                HardCutBoundaryProbePolicy.Outcome.NO_BOUNDED_PAUSE,
                HardCutBoundaryProbePolicy.Outcome.NO_BOUNDED_PAUSE,
            ),
            refinement.attempts.map { it.selection.outcome },
        )
    }
}
