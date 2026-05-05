package app.murmurnote.android.audio

import app.murmurnote.android.data.asr.WavReader
import app.murmurnote.android.util.Logger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Lightweight energy-based VAD: reads mono 16kHz PCM, computes per-frame RMS,
 * adaptively thresholds to find speech→silence transitions.
 *
 * No external model needed — pure math on PCM samples. Used as a supplementary
 * signal alongside ffmpeg silencedetect to catch pauses that a fixed dB threshold misses.
 */
@Singleton
class VadDetector @Inject constructor(
    private val logger: Logger
) {
    companion object {
        private const val FRAME_MS = 30
        private const val FRAME_SAMPLES = WavReader.TARGET_SR * FRAME_MS / 1000  // 480
        private const val MIN_SILENCE_FRAMES = 5   // 150ms hysteresis
        private const val SPEECH_PERCENTILE = 0.30 // top 30% frames = speech reference
        private const val SILENCE_RATIO = 0.12     // frames below 12% of speech avg = silence
        private const val MERGE_GAP_MS = 100L      // merge silences within 100ms of each other
    }

    data class EnergyFrame(val index: Int, val rms: Double, val timeMs: Long)

    /**
     * Returns silence ranges (in ms) detected by energy analysis.
     * Designed to be merged with ffmpeg silencedetect results for more complete coverage.
     */
    fun detect(file: File): List<AudioSplitter.SilenceRange> {
        val samples = WavReader.readMono16kPcm(file)
        val totalFrames = samples.size / FRAME_SAMPLES
        if (totalFrames < MIN_SILENCE_FRAMES * 2) {
            logger.d("Vad", "${file.name}: too short (${samples.size} samples, $totalFrames frames)")
            return emptyList()
        }

        // Compute RMS per frame
        val frames = (0 until totalFrames).map { i ->
            val offset = i * FRAME_SAMPLES
            var sumSq = 0.0
            for (j in offset until offset + FRAME_SAMPLES) {
                val s = samples[j].toDouble()
                sumSq += s * s
            }
            EnergyFrame(
                index = i,
                rms = sqrt(sumSq / FRAME_SAMPLES),
                timeMs = (i * FRAME_MS).toLong()
            )
        }

        // Adaptive threshold: speech energy = average of top SPEECH_PERCENTILE frames
        val sortedRms = frames.map { it.rms }.sortedDescending()
        val speechTop = sortedRms.take(((totalFrames * SPEECH_PERCENTILE).toInt()).coerceAtLeast(1))
        val speechAvgRms = speechTop.average()
        val silenceThreshold = speechAvgRms * SILENCE_RATIO

        if (speechAvgRms <= 0.0) {
            logger.d("Vad", "${file.name}: no detectable energy")
            return emptyList()
        }

        // Classify frames
        val isSilence = frames.map { it.rms < silenceThreshold }

        // Find runs of MIN_SILENCE_FRAMES consecutive silent frames
        val ranges = mutableListOf<AudioSplitter.SilenceRange>()
        var runStart = -1
        for (i in isSilence.indices) {
            if (isSilence[i]) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0 && (i - runStart) >= MIN_SILENCE_FRAMES) {
                    ranges.add(
                        AudioSplitter.SilenceRange(
                            startMs = frames[runStart].timeMs,
                            endMs = frames[i - 1].timeMs + FRAME_MS
                        )
                    )
                }
                runStart = -1
            }
        }
        // Trailing silence
        if (runStart >= 0 && (isSilence.size - runStart) >= MIN_SILENCE_FRAMES) {
            ranges.add(
                AudioSplitter.SilenceRange(
                    startMs = frames[runStart].timeMs,
                    endMs = frames.last().timeMs + FRAME_MS
                )
            )
        }

        // Merge adjacent silences within MERGE_GAP_MS
        val merged = mergeCloseRanges(ranges)
        logger.i(
            "Vad",
            "${file.name}: frames=$totalFrames speechAvg=%.4f thresh=%.4f raw=${ranges.size} merged=${merged.size}"
                .format(speechAvgRms, silenceThreshold)
        )
        return merged
    }

    private fun mergeCloseRanges(ranges: List<AudioSplitter.SilenceRange>): List<AudioSplitter.SilenceRange> {
        if (ranges.isEmpty()) return ranges
        val sorted = ranges.sortedBy { it.startMs }
        val result = mutableListOf<AudioSplitter.SilenceRange>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startMs - current.endMs <= MERGE_GAP_MS) {
                current = AudioSplitter.SilenceRange(
                    startMs = current.startMs,
                    endMs = next.endMs
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }
}
