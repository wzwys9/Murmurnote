package app.murmurnote.android.audio

/** Runs the canonical bounded hard-cut probe against PCM still retained by the live ring. */
internal class LiveHardCutBoundaryRefiner(
    private val store: LivePcmSegmentStore,
    private val sampleRateHz: Int,
    private val frameSizeSamples: Int = SileroVadDetector.WINDOW_SIZE_SAMPLES,
    private val sessionFactory: (NeuralVadSegmentPlanner.Preset) -> StreamingVadSession,
) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(frameSizeSamples > 0) { "VAD frame size must be positive" }
    }

    fun refine(
        request: NeuralVadSegmentPlanner.HardCutRequest,
    ): HardCutBoundaryProbePolicy.Refinement {
        val probeFile = store.outputDirectory.resolve(PROBE_FILE_NAME)
        return try {
            HardCutBoundaryProbePolicy.refine(
                hardLimitEndSample = request.hardLimitEndSample,
                recordingSampleCount = store.sampleCount,
                sampleRateHz = sampleRateHz,
            ) { window ->
                store.materialize(
                    output = probeFile,
                    startSample = window.analysisStartSample,
                    endSampleExclusive = window.analysisEndSampleExclusive,
                )
                val localDetection = PcmVadWindowRunner.detect(
                    file = probeFile,
                    frameSizeSamples = frameSizeSamples,
                    sessionFactory = {
                        sessionFactory(HardCutBoundaryProbePolicy.neuralVadPreset(window))
                    },
                )
                check(localDetection.sampleRateHz == sampleRateHz) {
                    "Live hard-cut probe observed a different sample rate"
                }
                localDetection.speechRanges.map { range ->
                    NeuralVadSegmentPlanner.SpeechRange(
                        startSample = Math.addExact(
                            window.analysisStartSample,
                            range.startSample,
                        ),
                        endSampleExclusive = Math.addExact(
                            window.analysisStartSample,
                            range.endSampleExclusive,
                        ),
                    )
                }
            }
        } finally {
            check(!probeFile.exists() || probeFile.delete()) {
                "Unable to remove the temporary live hard-cut probe"
            }
        }
    }

    private companion object {
        const val PROBE_FILE_NAME = ".hard_cut_probe.wav"
    }
}
