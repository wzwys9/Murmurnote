package app.murmurnote.android.audio

import java.io.File
import java.io.IOException

/** Feeds one bounded PCM window into a fresh VAD session and restores global sample indexes. */
internal object PcmVadWindowRunner {

    data class Result(
        val recordingSampleCount: Int,
        val sampleRateHz: Int,
        val analyzedStartSample: Int,
        val analyzedEndSampleExclusive: Int,
        val speechRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
    )

    fun detect(
        file: File,
        startSample: Int = 0,
        endSampleExclusive: Int? = null,
        frameSizeSamples: Int,
        sessionFactory: () -> StreamingVadSession,
    ): Result = Pcm16WavStreamReader(file).use { reader ->
        require(frameSizeSamples > 0) { "VAD frame size must be positive" }
        val endSample = endSampleExclusive ?: reader.sampleCount
        require(startSample in 0..reader.sampleCount) { "VAD window start is outside the WAV" }
        require(endSample in startSample..reader.sampleCount) { "VAD window end is outside the WAV" }

        if (startSample == endSample) {
            return@use Result(
                recordingSampleCount = reader.sampleCount,
                sampleRateHz = reader.sampleRateHz,
                analyzedStartSample = startSample,
                analyzedEndSampleExclusive = endSample,
                speechRanges = emptyList(),
            )
        }

        reader.seekToSample(startSample)
        val session = sessionFactory()
        var detectionFailure: Throwable? = null
        try {
            val nativeRanges = mutableListOf<NeuralVadSegmentPlanner.SpeechRange>()
            val frame = FloatArray(frameSizeSamples)
            var remainingSamples = endSample - startSample
            while (remainingSamples > 0) {
                val fileSamples = reader.readFrameInto(
                    destination = frame,
                    maxSampleCount = minOf(frame.size, remainingSamples),
                )
                    ?: throw IOException("WAV ended before the requested VAD window")
                nativeRanges += session.acceptFrame(frame)
                remainingSamples -= fileSamples
            }
            check(reader.nextSampleIndex == endSample) {
                "Bounded VAD read escaped its source window: " +
                    "expectedEnd=$endSample actualEnd=${reader.nextSampleIndex}"
            }
            nativeRanges += session.flush()

            Result(
                recordingSampleCount = reader.sampleCount,
                sampleRateHz = reader.sampleRateHz,
                analyzedStartSample = startSample,
                analyzedEndSampleExclusive = endSample,
                speechRanges = validateClipAndOffset(
                    nativeRanges = nativeRanges,
                    exactWindowSamples = endSample - startSample,
                    frameSizeSamples = frameSizeSamples,
                    globalStartSample = startSample,
                ),
            )
        } catch (failure: Throwable) {
            detectionFailure = failure
            throw failure
        } finally {
            try {
                session.close()
            } catch (releaseFailure: Throwable) {
                detectionFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
    }

    private fun validateClipAndOffset(
        nativeRanges: List<NeuralVadSegmentPlanner.SpeechRange>,
        exactWindowSamples: Int,
        frameSizeSamples: Int,
        globalStartSample: Int,
    ): List<NeuralVadSegmentPlanner.SpeechRange> = buildList {
        val paddedWindowSamples =
            ((exactWindowSamples.toLong() + frameSizeSamples - 1L) / frameSizeSamples) *
                frameSizeSamples
        var previousNativeEnd = 0
        nativeRanges.forEach { range ->
            val endSample = range.endSampleExclusive.toLong()
            check(
                range.startSample >= previousNativeEnd &&
                    range.endSampleExclusive > range.startSample &&
                    endSample <= paddedWindowSamples,
            ) {
                "Neural VAD returned an invalid window range: " +
                    "range=$range paddedSamples=$paddedWindowSamples previousEnd=$previousNativeEnd"
            }
            previousNativeEnd = range.endSampleExclusive
            if (range.startSample >= exactWindowSamples) return@forEach

            val clippedEnd = minOf(range.endSampleExclusive, exactWindowSamples)
            add(
                NeuralVadSegmentPlanner.SpeechRange(
                    startSample = Math.addExact(globalStartSample, range.startSample),
                    endSampleExclusive = Math.addExact(globalStartSample, clippedEnd),
                ),
            )
        }
    }
}
