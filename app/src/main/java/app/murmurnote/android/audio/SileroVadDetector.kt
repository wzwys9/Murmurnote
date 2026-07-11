package app.murmurnote.android.audio

import android.content.Context
import app.murmurnote.android.util.Logger
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the bundled Silero VAD over PCM frames without retaining the recording in memory. */
@Singleton
class SileroVadDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    data class Detection(
        val sampleCount: Int,
        val sampleRateHz: Int,
        val speechRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
    )

    @Volatile
    private var modelAssetValidated = false

    /**
     * Detects native speech ranges. Native segments are immediately reduced to start/end indexes;
     * their sample arrays are never retained by this wrapper.
     */
    @Throws(IOException::class)
    fun detect(file: File): Detection {
        val result = detectWindow(
            file = file,
            startSample = 0,
            endSampleExclusive = null,
            preset = NeuralVadSegmentPlanner.PRESET,
        )
        logger.i(
            "Vad",
            "offline Silero detection complete",
            fields = mapOf(
                "sampleCount" to result.recordingSampleCount,
                "speechRanges" to result.speechRanges.size,
                "preset" to NeuralVadSegmentPlanner.PRESET.version,
            ),
        )
        return Detection(
            sampleCount = result.recordingSampleCount,
            sampleRateHz = result.sampleRateHz,
            speechRanges = result.speechRanges,
        )
    }

    internal fun detectWindow(
        file: File,
        startSample: Int,
        endSampleExclusive: Int?,
        preset: NeuralVadSegmentPlanner.Preset,
    ): PcmVadWindowRunner.Result = PcmVadWindowRunner.detect(
        file = file,
        startSample = startSample,
        endSampleExclusive = endSampleExclusive,
        frameSizeSamples = WINDOW_SIZE_SAMPLES,
        sessionFactory = { openStreamingSession(preset) },
    )

    /** Opens one session for an entire recording. Callers must serialize access and close it. */
    internal fun openStreamingSession(
        preset: NeuralVadSegmentPlanner.Preset = NeuralVadSegmentPlanner.PRESET,
    ): StreamingVadSession {
        validateModelAsset()
        return NativeStreamingVadSession(
            Vad(assetManager = context.assets, config = nativeConfig(preset)),
        )
    }

    @Synchronized
    @Throws(IOException::class)
    private fun validateModelAsset() {
        if (modelAssetValidated) return

        val actualSize = try {
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MODEL_SIZE_BYTES) break
                }
                total
            }
        } catch (failure: IOException) {
            throw IOException("Bundled Silero VAD model is missing: $MODEL_ASSET_PATH", failure)
        }

        if (actualSize != MODEL_SIZE_BYTES) {
            throw IOException(
                "Bundled Silero VAD model has invalid size: expected=$MODEL_SIZE_BYTES actual=$actualSize",
            )
        }
        modelAssetValidated = true
    }

    private class NativeStreamingVadSession(
        private var vad: Vad?,
    ) : StreamingVadSession {
        private var acceptedPaddedSamples = 0
        private var previousEndSample = 0
        private var flushed = false

        override val isSpeechDetected: Boolean
            get() = vad?.isSpeechDetected() ?: false

        override fun acceptFrame(samples: FloatArray): List<NeuralVadSegmentPlanner.SpeechRange> {
            check(!flushed) { "Silero VAD session has already been flushed" }
            require(samples.size == WINDOW_SIZE_SAMPLES) {
                "Silero VAD requires $WINDOW_SIZE_SAMPLES-sample frames"
            }
            acceptedPaddedSamples = Math.addExact(acceptedPaddedSamples, samples.size)
            val active = checkNotNull(vad) { "Silero VAD session is closed" }
            active.acceptWaveform(samples)
            return drain(active)
        }

        override fun flush(): List<NeuralVadSegmentPlanner.SpeechRange> {
            if (flushed) return emptyList()
            flushed = true
            val active = checkNotNull(vad) { "Silero VAD session is closed" }
            active.flush()
            return drain(active)
        }

        override fun close() {
            val active = vad ?: return
            vad = null
            active.release()
        }

        private fun drain(active: Vad): List<NeuralVadSegmentPlanner.SpeechRange> = buildList {
            while (!active.empty()) {
                val native = active.front()
                val startSample = native.start
                val endSampleExclusive = startSample.toLong() + native.samples.size
                active.pop()

                check(
                    startSample >= previousEndSample &&
                        native.samples.isNotEmpty() &&
                        endSampleExclusive <= acceptedPaddedSamples.toLong(),
                ) {
                    "Silero VAD returned an invalid native range: " +
                        "start=$startSample samples=${native.samples.size} " +
                        "accepted=$acceptedPaddedSamples previousEnd=$previousEndSample"
                }
                add(
                    NeuralVadSegmentPlanner.SpeechRange(
                        startSample = startSample,
                        endSampleExclusive = endSampleExclusive.toInt(),
                    ),
                )
                previousEndSample = endSampleExclusive.toInt()
            }
        }
    }

    companion object {
        const val MODEL_ASSET_PATH = "vad_models/silero_vad_v5/silero_vad.onnx"
        const val MODEL_SIZE_BYTES = 2_313_101L
        const val SAMPLE_RATE_HZ = 16_000
        const val WINDOW_SIZE_SAMPLES = 512

        fun nativeConfig(
            preset: NeuralVadSegmentPlanner.Preset = NeuralVadSegmentPlanner.PRESET,
        ): VadModelConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET_PATH,
                threshold = preset.threshold,
                minSilenceDuration = preset.minSilenceMs / 1_000.0f,
                minSpeechDuration = preset.minSpeechMs / 1_000.0f,
                windowSize = WINDOW_SIZE_SAMPLES,
                maxSpeechDuration = preset.maxSegmentMs / 1_000.0f,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
    }
}
