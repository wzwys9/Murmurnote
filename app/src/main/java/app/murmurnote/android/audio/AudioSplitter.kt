package app.murmurnote.android.audio

import android.os.SystemClock
import app.murmurnote.android.util.Logger
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
    private val ffmpegCommandRunner: FfmpegCommandRunner,
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
        var hardCutRequestCount = 0
        var hardCutProbeAttemptCount = 0
        var fallbackWindowProbeCount = 0
        var rescueProbeCount = 0
        var refinedHardCutCount = 0
        var hardCutProbeElapsedMs = 0L
        val segments = NeuralVadSegmentPlanner.plan(
            sampleCount = detection.sampleCount,
            sampleRateHz = detection.sampleRateHz,
            speechRanges = detection.speechRanges,
            hardCutBoundaryRefiner = { request ->
                hardCutRequestCount += 1
                val attemptElapsedMs = mutableListOf<Long>()
                val refinement = HardCutBoundaryProbePolicy.refine(
                    hardLimitEndSample = request.hardLimitEndSample,
                    recordingSampleCount = detection.sampleCount,
                    sampleRateHz = detection.sampleRateHz,
                ) { window ->
                    val startedAt = SystemClock.elapsedRealtime()
                    hardCutProbeAttemptCount += 1
                    when (window.stage) {
                        HardCutBoundaryProbePolicy.ProbeStage.PRIMARY -> Unit
                        HardCutBoundaryProbePolicy.ProbeStage.FALLBACK -> {
                            fallbackWindowProbeCount += 1
                        }
                        HardCutBoundaryProbePolicy.ProbeStage.RESCUE -> {
                            rescueProbeCount += 1
                        }
                    }
                    val probe = sileroVadDetector.detectWindow(
                        file = input,
                        startSample = window.analysisStartSample,
                        endSampleExclusive = window.analysisEndSampleExclusive,
                        preset = HardCutBoundaryProbePolicy.neuralVadPreset(window),
                    )
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    attemptElapsedMs += elapsedMs
                    hardCutProbeElapsedMs += elapsedMs
                    check(
                        probe.recordingSampleCount == detection.sampleCount &&
                            probe.sampleRateHz == detection.sampleRateHz,
                    ) { "Hard-cut probe observed different source audio metadata" }
                    probe.speechRanges
                }

                refinement.attempts.forEachIndexed { index, attempt ->
                    val selection = attempt.selection
                    val profile = HardCutBoundaryProbePolicy.profile(attempt.window)
                    logger.d(
                        "Split",
                        "hard-cut boundary probe complete",
                        fields = mapOf(
                            "deadlineSample" to request.hardLimitEndSample,
                            "stage" to attempt.window.stage.name,
                            "searchWindowMs" to attempt.window.searchWindowMs,
                            "threshold" to profile.threshold,
                            "minSilenceMs" to profile.minSilenceMs,
                            "cutSample" to selection.cutSample,
                            "outcome" to selection.outcome.name,
                            "speechRanges" to selection.speechRangeCount,
                            "boundedPauses" to selection.boundedPauseCount,
                            "longestBoundedPauseMs" to samplesToMilliseconds(
                                selection.longestBoundedPauseSamples,
                                detection.sampleRateHz,
                            ),
                            "analysisSamples" to
                                (attempt.window.analysisEndSampleExclusive -
                                    attempt.window.analysisStartSample),
                            "elapsedMs" to attemptElapsedMs[index],
                        ),
                    )
                }
                refinement.cutSample.also { cutSample ->
                    if (cutSample != null) refinedHardCutCount += 1
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
                    it.cutReason == NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT ||
                        it.cutReason == NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT
                },
                "fallbackHardCuts" to segments.count {
                    it.cutReason == NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT
                },
                "hardCutRequests" to hardCutRequestCount,
                "hardCutProbeAttempts" to hardCutProbeAttemptCount,
                "fallbackWindowProbes" to fallbackWindowProbeCount,
                "rescueProbes" to rescueProbeCount,
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
            val result = try {
                ffmpegCommandRunner.execute(command)
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
            if (!result.isSuccess || !output.exists() || output.length() <= 0L) {
                output.delete()
                logger.e(
                    "Split",
                    "audio slice materialization failed",
                    fields = mapOf(
                        "segmentIndex" to index,
                        "startSample" to segment.startSample,
                        "endSample" to segment.endSampleExclusive,
                        "returnCode" to result.returnCode,
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
