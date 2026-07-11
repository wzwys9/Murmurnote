package app.murmurnote.android.audio

/** Frozen policy for the bounded secondary Silero pass used only before a forced cut. */
internal object HardCutBoundaryProbePolicy {

    data class Preset(
        val version: String,
        val threshold: Float,
        val minSilenceMs: Int,
        val searchWindowMs: Int,
        val warmupMs: Int,
        val lookaheadMs: Int,
        val endGuardMs: Int,
    )

    data class Window(
        val analysisStartSample: Int,
        val analysisEndSampleExclusive: Int,
        val searchStartSample: Int,
        val searchEndSampleExclusive: Int,
        val sampleRateHz: Int,
    )

    val PRESET = Preset(
        version = "hard_cut_probe_p065_ms200_win5000_warm500_look500_guard200",
        threshold = 0.65f,
        minSilenceMs = 200,
        searchWindowMs = 5_000,
        warmupMs = 500,
        lookaheadMs = 500,
        endGuardMs = 200,
    )

    val canonicalVadVersion: String
        get() = "${NeuralVadSegmentPlanner.PRESET.version}+${PRESET.version}"

    fun neuralVadPreset(): NeuralVadSegmentPlanner.Preset =
        NeuralVadSegmentPlanner.PRESET.copy(
            version = canonicalVadVersion,
            threshold = PRESET.threshold,
            minSilenceMs = PRESET.minSilenceMs,
            prePaddingMs = 0,
            postPaddingMs = 0,
        )

    fun window(
        hardLimitEndSample: Int,
        recordingSampleCount: Int,
        sampleRateHz: Int,
    ): Window {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(recordingSampleCount > 0) { "Recording must contain audio" }
        require(hardLimitEndSample in 1 until recordingSampleCount) {
            "Hard-cut deadline must be inside the recording"
        }

        val searchWindowSamples = PRESET.searchWindowMs.toSamples(sampleRateHz)
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
        )
    }

    fun selectCutSample(
        window: Window,
        speechRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
    ): Int? {
        val minimumSilenceSamples = PRESET.minSilenceMs.toSamples(window.sampleRateHz)
        var previous: NeuralVadSegmentPlanner.SpeechRange? = null
        val candidates = mutableListOf<Candidate>()

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
                if (gapEnd - gapStart >= minimumSilenceSamples) {
                    candidates += Candidate(gapStart, gapEnd)
                }
            }
            previous = current
        }

        return candidates
            .maxWithOrNull(compareBy<Candidate> { it.durationSamples }.thenBy { it.midpointSample })
            ?.midpointSample
    }

    private fun Int.toSamples(sampleRateHz: Int): Int =
        ((toLong() * sampleRateHz + 999L) / 1_000L).toInt()

    private data class Candidate(
        val startSample: Int,
        val endSampleExclusive: Int,
    ) {
        val durationSamples: Int = endSampleExclusive - startSample
        val midpointSample: Int = startSample + durationSamples / 2
    }
}
