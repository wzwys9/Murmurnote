package app.murmurnote.android.audio

/**
 * Converts already detected neural-VAD speech ranges into deterministic ASR segments.
 *
 * Model detection is deliberately outside this class. This planner only applies the frozen
 * padding policy, merges padded ranges, and enforces the hard input limit with overlap.
 */
object NeuralVadSegmentPlanner {

    data class Preset(
        val version: String,
        val threshold: Float,
        val minSpeechMs: Int,
        val minSilenceMs: Int,
        val prePaddingMs: Int,
        val postPaddingMs: Int,
        val maxSegmentMs: Int,
        val hardCutOverlapMs: Int,
        val minFinalSegmentMs: Int,
    )

    data class SpeechRange(
        val startSample: Int,
        val endSampleExclusive: Int,
    )

    data class Segment(
        val startSample: Int,
        val endSampleExclusive: Int,
        val cutReason: CutReason,
    )

    data class HardCutRequest(
        val segmentStartSample: Int,
        val hardLimitEndSample: Int,
    )

    enum class CutReason {
        NATURAL_PAUSE,
        HARD_LIMIT,
        END_OF_AUDIO,
    }

    val PRESET = Preset(
        version = "silero_v5_0_p050_s250_ms450_pre250_post250_max25000_ov500_min200",
        threshold = 0.5f,
        minSpeechMs = 250,
        minSilenceMs = 450,
        prePaddingMs = 250,
        postPaddingMs = 250,
        maxSegmentMs = 25_000,
        hardCutOverlapMs = 500,
        minFinalSegmentMs = 200,
    )

    fun plan(
        sampleCount: Int,
        sampleRateHz: Int,
        speechRanges: List<SpeechRange>,
        preset: Preset = PRESET,
        hardCutBoundaryRefiner: ((HardCutRequest) -> Int?)? = null,
    ): List<Segment> {
        validate(sampleCount, sampleRateHz, speechRanges, preset)
        if (speechRanges.isEmpty()) return emptyList()

        val prePaddingSamples = preset.prePaddingMs.toSamples(sampleRateHz, allowZero = true)
        val postPaddingSamples = preset.postPaddingMs.toSamples(sampleRateHz, allowZero = true)
        val maxSegmentSamples = preset.maxSegmentMs.toSamples(sampleRateHz)
        val overlapSamples = preset.hardCutOverlapMs.toSamples(sampleRateHz, allowZero = true)
        val minFinalSegmentSamples = preset.minFinalSegmentMs.toSamples(sampleRateHz)

        val paddedRanges = mutableListOf<SpeechRange>()
        speechRanges.forEach { range ->
            val paddedRange = SpeechRange(
                startSample = (range.startSample - prePaddingSamples).coerceAtLeast(0),
                endSampleExclusive = (range.endSampleExclusive.toLong() + postPaddingSamples)
                    .coerceAtMost(sampleCount.toLong())
                    .toInt(),
            )
            val previous = paddedRanges.lastOrNull()
            if (previous != null && paddedRange.startSample <= previous.endSampleExclusive) {
                paddedRanges[paddedRanges.lastIndex] = previous.copy(
                    endSampleExclusive = maxOf(
                        previous.endSampleExclusive,
                        paddedRange.endSampleExclusive,
                    ),
                )
            } else {
                paddedRanges += paddedRange
            }
        }

        return buildList {
            paddedRanges
                .filter { it.endSampleExclusive - it.startSample >= minFinalSegmentSamples }
                .forEach { range ->
                    var segmentStart = range.startSample
                    while (range.endSampleExclusive - segmentStart > maxSegmentSamples) {
                        val hardLimitEnd = segmentStart + maxSegmentSamples
                        var segmentEnd = hardCutBoundaryRefiner
                            ?.invoke(
                                HardCutRequest(
                                    segmentStartSample = segmentStart,
                                    hardLimitEndSample = hardLimitEnd,
                                ),
                            )
                            ?: hardLimitEnd
                        require(segmentEnd <= hardLimitEnd) {
                            "Refined hard-cut boundary exceeds the maximum segment duration"
                        }
                        require(segmentEnd - segmentStart > overlapSamples) {
                            "Refined hard-cut boundary cannot make forward progress"
                        }
                        val nextStart = segmentEnd - overlapSamples
                        val finalTailSamples = range.endSampleExclusive - nextStart
                        if (finalTailSamples in 1 until minFinalSegmentSamples) {
                            segmentEnd -= minFinalSegmentSamples - finalTailSamples
                        }

                        add(
                            Segment(
                                startSample = segmentStart,
                                endSampleExclusive = segmentEnd,
                                cutReason = CutReason.HARD_LIMIT,
                            ),
                        )
                        segmentStart = segmentEnd - overlapSamples
                    }

                    add(
                        Segment(
                            startSample = segmentStart,
                            endSampleExclusive = range.endSampleExclusive,
                            cutReason = if (range.endSampleExclusive == sampleCount) {
                                CutReason.END_OF_AUDIO
                            } else {
                                CutReason.NATURAL_PAUSE
                            },
                        ),
                    )
                }
        }
    }

    private fun validate(
        sampleCount: Int,
        sampleRateHz: Int,
        speechRanges: List<SpeechRange>,
        preset: Preset,
    ) {
        require(sampleCount >= 0) { "Sample count cannot be negative" }
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(preset.version.isNotBlank()) { "Preset version cannot be blank" }
        require(preset.threshold in 0.0f..1.0f) { "VAD threshold must be between 0 and 1" }
        require(preset.minSpeechMs > 0) { "Minimum speech duration must be positive" }
        require(preset.minSilenceMs >= 0) { "Minimum silence duration cannot be negative" }
        require(preset.prePaddingMs >= 0 && preset.postPaddingMs >= 0) {
            "Padding cannot be negative"
        }
        require(preset.maxSegmentMs > 0) { "Maximum segment duration must be positive" }
        require(preset.hardCutOverlapMs in 0 until preset.maxSegmentMs) {
            "Hard-cut overlap must be smaller than the maximum segment duration"
        }
        require(preset.minFinalSegmentMs in 1..(preset.maxSegmentMs / 2)) {
            "Minimum final segment must be positive and at most half the maximum duration"
        }

        var previous: SpeechRange? = null
        speechRanges.forEach { range ->
            require(
                range.startSample >= 0 &&
                    range.endSampleExclusive > range.startSample &&
                    range.endSampleExclusive <= sampleCount,
            ) { "Speech range is outside the audio: $range / $sampleCount" }

            previous?.let { preceding ->
                require(range.startSample >= preceding.startSample) {
                    "Speech ranges must be sorted by start sample"
                }
                require(range.startSample >= preceding.endSampleExclusive) {
                    "Speech ranges must not overlap"
                }
            }
            previous = range
        }
    }

    private fun Int.toSamples(sampleRateHz: Int, allowZero: Boolean = false): Int {
        val samples = toLong() * sampleRateHz / MILLIS_PER_SECOND
        val minimum = if (allowZero) 0L else 1L
        require(samples in minimum..Int.MAX_VALUE.toLong()) {
            "Duration is outside the supported sample range: $this ms"
        }
        return samples.toInt()
    }

    private const val MILLIS_PER_SECOND = 1_000L
}
