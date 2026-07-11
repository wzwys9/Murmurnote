package app.murmurnote.android.audio

import java.io.File

internal data class LiveVadAudioSegment(
    val sequence: Int,
    val file: File,
    val startMs: Long,
    val endMs: Long,
    val cutReason: NeuralVadSegmentPlanner.CutReason,
    val overlapBeforeMs: Long,
    val vadPresetVersion: String,
) {
    /** Live text/audio is always preview-only; final offline recognition is the canonical source. */
    val isCanonical: Boolean = false
}

/** Worker-side bridge from queued PCM to a session-level Silero coordinator and preview WAVs. */
internal class LiveNeuralVadPcmProcessor(
    session: StreamingVadSession,
    outputDirectory: File,
    private val sampleRateHz: Int = SileroVadDetector.SAMPLE_RATE_HZ,
    private val preset: NeuralVadSegmentPlanner.Preset = NeuralVadSegmentPlanner.PRESET,
    hardCutSessionFactory: ((NeuralVadSegmentPlanner.Preset) -> StreamingVadSession)? = null,
    private val onHardCutRefinement: (HardCutBoundaryProbePolicy.Refinement) -> Unit = {},
    private val onSegment: (LiveVadAudioSegment) -> Unit,
) : LivePcmProcessor {
    private val store = LivePcmSegmentStore(
        outputDirectory = outputDirectory,
        sampleRateHz = sampleRateHz,
        capacitySamples = requiredRingCapacitySamples(sampleRateHz, preset),
    )
    private var sequence = 0
    private var previousEndSample = 0
    private var finished = false
    private val hardCutRefiner = hardCutSessionFactory?.let { sessionFactory ->
        LiveHardCutBoundaryRefiner(
            store = store,
            sampleRateHz = sampleRateHz,
            sessionFactory = sessionFactory,
        )
    }
    private val coordinator = IncrementalNeuralVadCoordinator(
        session = session,
        sampleRateHz = sampleRateHz,
        frameSizeSamples = SileroVadDetector.WINDOW_SIZE_SAMPLES,
        preset = preset,
        hardCutRefinementLookaheadMs = if (hardCutRefiner != null) {
            HardCutBoundaryProbePolicy.PRESET.lookaheadMs
        } else {
            0
        },
        hardCutBoundaryRefiner = hardCutRefiner?.let { refiner ->
            { request ->
                refiner.refine(request).also(onHardCutRefinement).cutSample
            }
        },
        onSegment = ::materialize,
    )

    override fun acceptPcm(pcm16Le: ByteArray) {
        check(!finished) { "Live neural VAD processor has already finished" }
        // Append the whole accepted capture chunk before inference can emit a boundary inside it.
        store.append(pcm16Le)
        coordinator.acceptPcm(pcm16Le)
    }

    override fun finish() {
        if (finished) return
        finished = true
        coordinator.finish()
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            coordinator.close()
        } catch (t: Throwable) {
            failure = t
        }
        try {
            store.close()
        } catch (storeFailure: Throwable) {
            failure?.addSuppressed(storeFailure) ?: run { failure = storeFailure }
        }
        failure?.let { throw it }
    }

    private fun materialize(segment: NeuralVadSegmentPlanner.Segment) {
        val output = store.outputDirectory.resolve(
            "segment_${sequence.toString().padStart(4, '0')}.wav",
        )
        store.materialize(output, segment.startSample, segment.endSampleExclusive)
        val overlapSamples = (previousEndSample - segment.startSample).coerceAtLeast(0)
        val preview = LiveVadAudioSegment(
            sequence = sequence,
            file = output,
            startMs = samplesToMilliseconds(segment.startSample),
            endMs = samplesToMilliseconds(segment.endSampleExclusive),
            cutReason = segment.cutReason,
            overlapBeforeMs = samplesToMilliseconds(overlapSamples),
            vadPresetVersion = if (hardCutRefiner != null) {
                HardCutBoundaryProbePolicy.canonicalVadVersion
            } else {
                preset.version
            },
        )
        check(preview.endMs > preview.startMs) { "Live neural VAD emitted a zero-duration segment" }
        onSegment(preview)
        previousEndSample = segment.endSampleExclusive
        sequence += 1
    }

    private fun samplesToMilliseconds(samples: Int): Long =
        samples.toLong() * 1_000L / sampleRateHz

    private companion object {
        fun requiredRingCapacitySamples(
            sampleRateHz: Int,
            preset: NeuralVadSegmentPlanner.Preset,
        ): Int {
            val publicationDelayMs = maxOf(
                preset.minSilenceMs,
                preset.postPaddingMs + preset.prePaddingMs + preset.minSpeechMs,
                HardCutBoundaryProbePolicy.PRESET.lookaheadMs,
            )
            val frameGuardMs =
                (2L * SileroVadDetector.WINDOW_SIZE_SAMPLES * 1_000L + sampleRateHz - 1L) /
                    sampleRateHz
            val retainedMs =
                preset.maxSegmentMs.toLong() + preset.prePaddingMs + publicationDelayMs + frameGuardMs
            return ((retainedMs * sampleRateHz + 999L) / 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
}
