package app.murmurnote.android.audio

import android.os.SystemClock
import app.murmurnote.android.util.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Splits mono PCM WAV input exclusively from bundled Silero VAD speech ranges.
 *
 * Silero decides where speech exists, [NeuralVadSegmentPlanner] applies the frozen padding and
 * hard-limit policy, and ffmpeg is used only to materialize those already-decided sample ranges.
 * Detection errors deliberately propagate; an energy or silence-threshold fallback would change
 * the recording's frozen ASR input contract.
 */
@Singleton
class AudioSplitter @Inject constructor(
    private val sileroVadDetector: SileroVadDetector,
    private val logger: Logger,
) {

    companion object {
        const val MAX_SEGMENT_MS: Long = 25_000L
    }

    /** [startMs, endMs) maps this materialized slice back to the original recording. */
    data class Slice(
        val file: File,
        val startMs: Long,
        val endMs: Long,
        val cutReason: NeuralVadSegmentPlanner.CutReason? = null,
        val overlapBeforeMs: Long = 0L,
    )

    suspend fun split(input: File, outputDir: File): List<Slice> {
        outputDir.mkdirs()

        val detection = sileroVadDetector.detect(input)
        var hardCutProbeCount = 0
        var refinedHardCutCount = 0
        var hardCutProbeElapsedMs = 0L
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = detection.sampleCount,
            sampleRateHz = detection.sampleRateHz,
            speechRanges = detection.speechRanges,
            hardCutBoundaryRefiner = { request ->
                val window = HardCutBoundaryProbePolicy.window(
                    hardLimitEndSample = request.hardLimitEndSample,
                    recordingSampleCount = detection.sampleCount,
                    sampleRateHz = detection.sampleRateHz,
                )
                val startedAt = SystemClock.elapsedRealtime()
                hardCutProbeCount += 1
                val probe = sileroVadDetector.detectWindow(
                    file = input,
                    startSample = window.analysisStartSample,
                    endSampleExclusive = window.analysisEndSampleExclusive,
                    preset = HardCutBoundaryProbePolicy.neuralVadPreset(),
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                hardCutProbeElapsedMs += elapsedMs
                check(
                    probe.recordingSampleCount == detection.sampleCount &&
                        probe.sampleRateHz == detection.sampleRateHz,
                ) { "Hard-cut probe observed different source audio metadata" }

                HardCutBoundaryProbePolicy
                    .selectCutSample(window, probe.speechRanges)
                    .also { cutSample ->
                        if (cutSample != null) refinedHardCutCount += 1
                        logger.d(
                            "Split",
                            "hard-cut boundary probe complete",
                            fields = mapOf(
                                "deadlineSample" to request.hardLimitEndSample,
                                "cutSample" to cutSample,
                                "analysisSamples" to
                                    (window.analysisEndSampleExclusive - window.analysisStartSample),
                                "elapsedMs" to elapsedMs,
                            ),
                        )
                    }
            },
        )

        logger.i(
            "Split",
            "neural VAD segmentation planned",
            fields = mapOf(
                "sampleCount" to detection.sampleCount,
                "speechRanges" to detection.speechRanges.size,
                "segments" to segments.size,
                "hardCuts" to segments.count {
                    it.cutReason == NeuralVadSegmentPlanner.CutReason.HARD_LIMIT
                },
                "hardCutProbes" to hardCutProbeCount,
                "refinedHardCuts" to refinedHardCutCount,
                "hardCutProbeElapsedMs" to hardCutProbeElapsedMs,
                "preset" to HardCutBoundaryProbePolicy.canonicalVadVersion,
            ),
        )

        if (segments.isEmpty()) return emptyList()
        return materializeSegments(
            input = input,
            outputDir = outputDir,
            sampleRateHz = detection.sampleRateHz,
            segments = segments,
        )
    }

    private suspend fun materializeSegments(
        input: File,
        outputDir: File,
        sampleRateHz: Int,
        segments: List<NeuralVadSegmentPlanner.Segment>,
    ): List<Slice> {
        val results = ArrayList<Slice>(segments.size)
        segments.forEachIndexed { index, segment ->
            val segmentSamples = segment.endSampleExclusive - segment.startSample
            val maxSegmentSamples =
                NeuralVadSegmentPlanner.PRESET.maxSegmentMs.toLong() * sampleRateHz / 1_000L
            check(segmentSamples > 0 && segmentSamples <= maxSegmentSamples) {
                "Neural VAD planner produced an invalid segment length: $segmentSamples samples"
            }

            val output = File(
                outputDir,
                "${input.nameWithoutExtension}_seg${index.toString().padStart(2, '0')}.wav",
            )
            if (output.exists() && !output.delete()) {
                error("Unable to replace an existing audio slice")
            }

            val startSeconds = segment.startSample / sampleRateHz.toDouble()
            val durationSeconds = segmentSamples / sampleRateHz.toDouble()
            val command = buildString {
                append("-y -i ").append(quote(input.absolutePath))
                append(" -ss ").append(startSeconds)
                append(" -t ").append(durationSeconds)
                append(" -ac 1 -ar 16000 -c:a pcm_s16le -f wav ")
                append(quote(output.absolutePath))
            }
            val completed = CompletableDeferred<Boolean>()
            FFmpegKit.executeAsync(command) { session ->
                completed.complete(ReturnCode.isSuccess(session.returnCode))
            }
            if (!completed.await() || !output.exists() || output.length() <= 0L) {
                logger.e(
                    "Split",
                    "audio slice materialization failed",
                    fields = mapOf(
                        "segmentIndex" to index,
                        "startSample" to segment.startSample,
                        "endSample" to segment.endSampleExclusive,
                    ),
                )
                error("ffmpeg failed to materialize neural VAD segment $index")
            }

            val previous = segments.getOrNull(index - 1)
            val overlapSamples = previous
                ?.let { it.endSampleExclusive - segment.startSample }
                ?.coerceAtLeast(0)
                ?: 0
            val startMs = samplesToMilliseconds(segment.startSample, sampleRateHz)
            val endMs = samplesToMilliseconds(segment.endSampleExclusive, sampleRateHz)
            val overlapBeforeMs = samplesToMilliseconds(overlapSamples, sampleRateHz)
            check(endMs > startMs && endMs - startMs <= MAX_SEGMENT_MS) {
                "Neural VAD slice timestamp is outside the 25-second limit: [$startMs, $endMs)"
            }

            results += Slice(
                file = output,
                startMs = startMs,
                endMs = endMs,
                cutReason = segment.cutReason,
                overlapBeforeMs = overlapBeforeMs,
            )
            logger.d(
                "Split",
                "audio slice materialized",
                fields = mapOf(
                    "segmentIndex" to index,
                    "durationMs" to (endMs - startMs),
                    "overlapBeforeMs" to overlapBeforeMs,
                    "cutReason" to segment.cutReason,
                    "outputBytes" to output.length(),
                ),
            )
        }
        return results
    }

    private fun samplesToMilliseconds(samples: Int, sampleRateHz: Int): Long =
        samples.toLong() * 1_000L / sampleRateHz

    private fun quote(path: String): String = "'${path.replace("'", "'\\''")}'"

}
