package app.murmurnote.android.audio

/** Frozen policy for the bounded secondary Silero pass used only before a forced cut. */
internal object HardCutBoundaryProbePolicy {

    data class Preset(
        val version: String,
        val threshold: Float,
        val minSilenceMs: Int,
        val fallbackThreshold: Float,
        val fallbackMinSilenceMs: Int,
        val rescueThreshold: Float,
        val rescueMinSilenceMs: Int,
        val primarySearchWindowMs: Int,
        val fallbackSearchWindowMs: Int,
        val warmupMs: Int,
        val lookaheadMs: Int,
        val endGuardMs: Int,
    )

    enum class ProbeStage {
        PRIMARY,
        FALLBACK,
        RESCUE,
    }

    data class Window(
        val analysisStartSample: Int,
        val analysisEndSampleExclusive: Int,
        val searchStartSample: Int,
        val searchEndSampleExclusive: Int,
        val sampleRateHz: Int,
        val searchWindowMs: Int,
        val stage: ProbeStage,
    )

    data class ProbeProfile(
        val threshold: Float,
        val minSilenceMs: Int,
    )

    enum class Outcome {
        REFINED,
        NO_SPEECH_RANGES,
        NO_BOUNDED_PAUSE,
        PAUSES_TOO_SHORT,
    }

    data class Selection(
        val cutSample: Int?,
        val outcome: Outcome,
        val speechRangeCount: Int,
        val boundedPauseCount: Int,
        val longestBoundedPauseSamples: Int,
    )

    data class Attempt(
        val window: Window,
        val selection: Selection,
    )

    data class Refinement(
        val cutSample: Int?,
        val attempts: List<Attempt>,
    )

    val PRESET = Preset(
        version =
            "hard_cut_probe_p065_ms200_win5000_fallback_p075_ms140_win10000_" +
                "rescue_p082_ms120_nearest_warm500_look500_guard200",
        threshold = 0.65f,
        minSilenceMs = 200,
        fallbackThreshold = 0.75f,
        fallbackMinSilenceMs = 140,
        rescueThreshold = 0.82f,
        rescueMinSilenceMs = 120,
        primarySearchWindowMs = 5_000,
        fallbackSearchWindowMs = 10_000,
        warmupMs = 500,
        lookaheadMs = 500,
        endGuardMs = 200,
    )

    val canonicalVadVersion: String
        get() = "${NeuralVadSegmentPlanner.PRESET.version}+${PRESET.version}"

    fun profile(window: Window? = null): ProbeProfile =
        when (window?.stage ?: ProbeStage.PRIMARY) {
            ProbeStage.PRIMARY -> ProbeProfile(
                threshold = PRESET.threshold,
                minSilenceMs = PRESET.minSilenceMs,
            )
            ProbeStage.FALLBACK -> ProbeProfile(
                threshold = PRESET.fallbackThreshold,
                minSilenceMs = PRESET.fallbackMinSilenceMs,
            )
            ProbeStage.RESCUE -> ProbeProfile(
                threshold = PRESET.rescueThreshold,
                minSilenceMs = PRESET.rescueMinSilenceMs,
            )
        }

    fun neuralVadPreset(window: Window? = null): NeuralVadSegmentPlanner.Preset {
        val profile = profile(window)
        return NeuralVadSegmentPlanner.PRESET.copy(
            version = canonicalVadVersion,
            threshold = profile.threshold,
            minSilenceMs = profile.minSilenceMs,
            prePaddingMs = 0,
            postPaddingMs = 0,
        )
    }

    fun window(
        hardLimitEndSample: Int,
        recordingSampleCount: Int,
        sampleRateHz: Int,
        searchWindowMs: Int = PRESET.primarySearchWindowMs,
        stage: ProbeStage = if (searchWindowMs == PRESET.primarySearchWindowMs) {
            ProbeStage.PRIMARY
        } else {
            ProbeStage.FALLBACK
        },
    ): Window {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(recordingSampleCount > 0) { "Recording must contain audio" }
        require(hardLimitEndSample in 1 until recordingSampleCount) {
            "Hard-cut deadline must be inside the recording"
        }
        require(searchWindowMs > PRESET.endGuardMs) {
            "Hard-cut search window must exceed its end guard"
        }

        val searchWindowSamples = searchWindowMs.toSamples(sampleRateHz)
        val warmupSamples = PRESET.warmupMs.toSamples(sampleRateHz)
        val lookaheadSamples = PRESET.lookaheadMs.toSamples(sampleRateHz)
        val endGuardSamples = PRESET.endGuardMs.toSamples(sampleRateHz)
        val searchStart = (hardLimitEndSample - searchWindowSamples).coerceAtLeast(0)
        val searchEnd = hardLimitEndSample - endGuardSamples
        require(searchEnd > searchStart) { "Hard-cut search window is empty" }

        return Window(
            analysisStartSample = (searchStart - warmupSamples).coerceAtLeast(0),
            analysisEndSampleExclusive = (hardLimitEndSample.toLong() + lookaheadSamples)
                .coerceAtMost(recordingSampleCount.toLong())
                .toInt(),
            searchStartSample = searchStart,
            searchEndSampleExclusive = searchEnd,
            sampleRateHz = sampleRateHz,
            searchWindowMs = searchWindowMs,
            stage = stage,
        )
    }

    fun refine(
        hardLimitEndSample: Int,
        recordingSampleCount: Int,
        sampleRateHz: Int,
        detectSpeechRanges: (Window) -> List<NeuralVadSegmentPlanner.SpeechRange>,
    ): Refinement {
        val attempts = mutableListOf<Attempt>()
        val probeStages = listOf(
            ProbeStage.PRIMARY to PRESET.primarySearchWindowMs,
            ProbeStage.FALLBACK to PRESET.fallbackSearchWindowMs,
            ProbeStage.RESCUE to PRESET.fallbackSearchWindowMs,
        )

        probeStages.forEach { (stage, searchWindowMs) ->
            val window = window(
                hardLimitEndSample = hardLimitEndSample,
                recordingSampleCount = recordingSampleCount,
                sampleRateHz = sampleRateHz,
                searchWindowMs = searchWindowMs,
                stage = stage,
            )
            val attempt = Attempt(
                window = window,
                selection = selectCut(window, detectSpeechRanges(window)),
            )
            attempts += attempt
            if (attempt.selection.cutSample != null) {
                return Refinement(attempt.selection.cutSample, attempts)
            }
        }
        return Refinement(cutSample = null, attempts = attempts)
    }

    fun selectCut(
        window: Window,
        speechRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
    ): Selection {
        val minimumSilenceSamples = profile(window).minSilenceMs.toSamples(window.sampleRateHz)
        var previous: NeuralVadSegmentPlanner.SpeechRange? = null
        val candidates = mutableListOf<Candidate>()
        var boundedPauseCount = 0
        var longestBoundedPauseSamples = 0

        speechRanges.forEach { current ->
            require(current.startSample >= window.analysisStartSample) {
                "Probe speech range starts before the analyzed window"
            }
            require(current.endSampleExclusive <= window.analysisEndSampleExclusive) {
                "Probe speech range ends after the analyzed window"
            }
            require(current.endSampleExclusive > current.startSample) {
                "Probe speech range must not be empty"
            }

            previous?.let { left ->
                require(current.startSample >= left.endSampleExclusive) {
                    "Probe speech ranges must be sorted and non-overlapping"
                }
                val gapStart = maxOf(left.endSampleExclusive, window.searchStartSample)
                val gapEnd = minOf(current.startSample, window.searchEndSampleExclusive)
                val gapSamples = gapEnd - gapStart
                if (gapSamples > 0) {
                    boundedPauseCount += 1
                    longestBoundedPauseSamples = maxOf(longestBoundedPauseSamples, gapSamples)
                }
                if (gapSamples >= minimumSilenceSamples) {
                    candidates += Candidate(gapStart, gapEnd)
                }
            }
            previous = current
        }

        val cutSample = candidates.maxByOrNull(Candidate::midpointSample)?.midpointSample
        val outcome = when {
            cutSample != null -> Outcome.REFINED
            speechRanges.isEmpty() -> Outcome.NO_SPEECH_RANGES
            boundedPauseCount == 0 -> Outcome.NO_BOUNDED_PAUSE
            else -> Outcome.PAUSES_TOO_SHORT
        }
        return Selection(
            cutSample = cutSample,
            outcome = outcome,
            speechRangeCount = speechRanges.size,
            boundedPauseCount = boundedPauseCount,
            longestBoundedPauseSamples = longestBoundedPauseSamples,
        )
    }

    private fun Int.toSamples(sampleRateHz: Int): Int =
        ((toLong() * sampleRateHz + 999L) / 1_000L).toInt()

    private data class Candidate(
        val startSample: Int,
        val endSampleExclusive: Int,
    ) {
        val midpointSample: Int = startSample + (endSampleExclusive - startSample) / 2
    }
}
