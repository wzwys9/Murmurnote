package app.murmurnote.android.audio

import java.io.Closeable

/** A single, serial neural-VAD session. Implementations consume one fixed-size float frame. */
internal interface StreamingVadSession : Closeable {
    val isSpeechDetected: Boolean
    fun acceptFrame(samples: FloatArray): List<NeuralVadSegmentPlanner.SpeechRange>
    fun flush(): List<NeuralVadSegmentPlanner.SpeechRange>
}

/**
 * Converts an arbitrary PCM16-LE stream into fixed Silero frames and publishes only stable planner
 * segments. A natural-pause segment is held until enough future audio has arrived to prove that a
 * later pre-padded speech range cannot merge into it. The final partial frame is zero-padded only
 * for inference; [acceptedSampleCount] and published timestamps always use the exact PCM length.
 */
internal class IncrementalNeuralVadCoordinator(
    private val session: StreamingVadSession,
    private val sampleRateHz: Int,
    private val frameSizeSamples: Int,
    private val preset: NeuralVadSegmentPlanner.Preset = NeuralVadSegmentPlanner.PRESET,
    private val hardCutRefinementLookaheadMs: Int = 0,
    private val hardCutBoundaryRefiner: ((NeuralVadSegmentPlanner.HardCutRequest) -> Int?)? = null,
    private val onSegment: (NeuralVadSegmentPlanner.Segment) -> Unit,
) : Closeable {

    private val frame = FloatArray(frameSizeSamples)
    private val speechRanges = mutableListOf<NeuralVadSegmentPlanner.SpeechRange>()
    private val publishedSegments = mutableListOf<NeuralVadSegmentPlanner.Segment>()
    private var frameFill = 0
    private var publishedSegmentCount = 0
    private var nextStabilityCheckSample = Int.MAX_VALUE
    private var neuralObservedSampleCount = 0
    private var finished = false
    private var closed = false
    private val hardCutRefinementCache = mutableMapOf<NeuralVadSegmentPlanner.HardCutRequest, Int?>()

    var acceptedSampleCount: Int = 0
        private set

    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(frameSizeSamples > 0) { "VAD frame size must be positive" }
        require(hardCutRefinementLookaheadMs >= 0) {
            "Hard-cut refinement lookahead cannot be negative"
        }
    }

    fun acceptPcm(pcm16Le: ByteArray) {
        check(!finished) { "Streaming VAD session has already finished" }
        require(pcm16Le.size % PCM16_BYTES_PER_SAMPLE == 0) {
            "PCM16 input must contain complete little-endian samples"
        }
        if (pcm16Le.isEmpty()) return

        var offset = 0
        var detectedNewRange = false
        while (offset < pcm16Le.size) {
            val low = pcm16Le[offset].toInt() and 0xff
            val high = pcm16Le[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            frame[frameFill++] = sample / PCM16_SCALE
            acceptedSampleCount = Math.addExact(acceptedSampleCount, 1)
            offset += PCM16_BYTES_PER_SAMPLE

            if (frameFill == frameSizeSamples) {
                detectedNewRange = addRanges(session.acceptFrame(frame), acceptedSampleCount) ||
                    detectedNewRange
                neuralObservedSampleCount = acceptedSampleCount
                frameFill = 0
            }
        }

        if (detectedNewRange || neuralObservedSampleCount >= nextStabilityCheckSample) {
            publishStableSegments(finalFlush = false)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        var failure: Throwable? = null
        try {
            if (frameFill > 0) {
                frame.fill(0.0f, frameFill, frame.size)
                addRanges(session.acceptFrame(frame), acceptedSampleCount)
                neuralObservedSampleCount = acceptedSampleCount
                frameFill = 0
            }
            addRanges(session.flush(), acceptedSampleCount)
            publishStableSegments(finalFlush = true)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try {
                close()
            } catch (closeFailure: Throwable) {
                failure?.addSuppressed(closeFailure) ?: throw closeFailure
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private fun addRanges(
        detected: List<NeuralVadSegmentPlanner.SpeechRange>,
        exactSampleCount: Int,
    ): Boolean {
        var added = false
        detected.forEach { nativeRange ->
            require(nativeRange.startSample >= 0) { "Neural VAD returned a negative start sample" }
            require(nativeRange.endSampleExclusive > nativeRange.startSample) {
                "Neural VAD returned an empty speech range"
            }

            // The final inference frame may contain bounded zero padding. It is never part of the
            // recording timeline and therefore cannot appear in a preview boundary.
            if (nativeRange.startSample >= exactSampleCount) return@forEach
            val clipped = nativeRange.copy(
                endSampleExclusive = minOf(nativeRange.endSampleExclusive, exactSampleCount),
            )
            val previous = speechRanges.lastOrNull()
            require(previous == null || clipped.startSample >= previous.endSampleExclusive) {
                "Neural VAD returned unsorted or overlapping speech ranges"
            }
            speechRanges += clipped
            added = true
        }
        return added
    }

    private fun publishStableSegments(finalFlush: Boolean) {
        if (speechRanges.isEmpty()) {
            nextStabilityCheckSample = Int.MAX_VALUE
            return
        }
        val planningSpeechRanges = if (!finalFlush && session.isSpeechDetected) {
            val last = speechRanges.last()
            if (last.endSampleExclusive < acceptedSampleCount) {
                speechRanges.dropLast(1) + last.copy(endSampleExclusive = acceptedSampleCount)
            } else {
                speechRanges
            }
        } else {
            speechRanges
        }
        val lookaheadSamples = millisecondsToSamples(hardCutRefinementLookaheadMs)
        val plan = NeuralVadSegmentPlanner.plan(
            sampleCount = acceptedSampleCount,
            sampleRateHz = sampleRateHz,
            speechRanges = planningSpeechRanges,
            preset = preset,
            hardCutBoundaryRefiner = hardCutBoundaryRefiner?.let { refiner ->
                { request ->
                    val probeReady = finalFlush ||
                        acceptedSampleCount >= safeAdd(request.hardLimitEndSample, lookaheadSamples)
                    when {
                        !probeReady -> null
                        hardCutRefinementCache.containsKey(request) ->
                            hardCutRefinementCache[request]
                        else -> refiner(request).also { result ->
                            hardCutRefinementCache[request] = result
                        }
                    }
                }
            },
        )
        check(publishedSegmentCount <= plan.size) {
            "Incremental neural VAD plan invalidated an already published segment"
        }
        publishedSegments.forEachIndexed { index, published ->
            val current = plan[index]
            check(
                published.startSample == current.startSample &&
                    published.endSampleExclusive == current.endSampleExclusive,
            ) {
                "Incremental neural VAD changed an already published boundary"
            }
        }

        val prePaddingSamples = millisecondsToSamples(preset.prePaddingMs)
        val minSpeechSamples = millisecondsToSamples(preset.minSpeechMs)
        val maxSegmentSamples = millisecondsToSamples(preset.maxSegmentMs)
        nextStabilityCheckSample = Int.MAX_VALUE
        while (publishedSegmentCount < plan.size) {
            val candidate = plan[publishedSegmentCount]
            val continuingSpeechHardLimit =
                !finalFlush &&
                    candidate.cutReason == NeuralVadSegmentPlanner.CutReason.END_OF_AUDIO &&
                    session.isSpeechDetected &&
                    candidate.endSampleExclusive - candidate.startSample >= maxSegmentSamples
            val hardLimitProbeReady = neuralObservedSampleCount >= safeAdd(
                safeAdd(candidate.startSample, maxSegmentSamples),
                lookaheadSamples,
            )
            val stable = when {
                finalFlush -> true
                continuingSpeechHardLimit -> hardLimitProbeReady
                candidate.cutReason == NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT ||
                    candidate.cutReason == NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT ->
                    hardLimitProbeReady
                candidate.cutReason == NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE ->
                    !session.isSpeechDetected &&
                        neuralObservedSampleCount >
                        safeAdd(
                            candidate.endSampleExclusive,
                            safeAdd(prePaddingSamples, minSpeechSamples),
                        )
                else -> false
            }
            if (!stable) {
                nextStabilityCheckSample = if (continuingSpeechHardLimit) {
                    safeAdd(
                        safeAdd(candidate.startSample, maxSegmentSamples),
                        lookaheadSamples,
                    )
                } else when (candidate.cutReason) {
                    NeuralVadSegmentPlanner.CutReason.NATURAL_PAUSE ->
                        safeAdd(
                            safeAdd(
                                candidate.endSampleExclusive,
                                safeAdd(prePaddingSamples, minSpeechSamples),
                            ),
                            1,
                        )
                    NeuralVadSegmentPlanner.CutReason.END_OF_AUDIO -> {
                        val lastSpeechEnd = speechRanges.last().endSampleExclusive
                        safeAdd(
                            safeAdd(
                                lastSpeechEnd,
                                safeAdd(
                                    prePaddingSamples,
                                    safeAdd(
                                        millisecondsToSamples(preset.postPaddingMs),
                                        minSpeechSamples,
                                    ),
                                ),
                            ),
                            1,
                        )
                    }
                    NeuralVadSegmentPlanner.CutReason.REFINED_HARD_LIMIT,
                    NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT ->
                        safeAdd(
                            safeAdd(candidate.startSample, maxSegmentSamples),
                            lookaheadSamples,
                        )
                }
                break
            }
            val published = if (continuingSpeechHardLimit) {
                candidate.copy(
                    cutReason = NeuralVadSegmentPlanner.CutReason.FALLBACK_HARD_LIMIT,
                )
            } else {
                candidate
            }
            onSegment(published)
            publishedSegments += published
            publishedSegmentCount += 1
        }
    }

    private fun millisecondsToSamples(milliseconds: Int): Int =
        (milliseconds.toLong() * sampleRateHz / MILLIS_PER_SECOND)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun safeAdd(left: Int, right: Int): Int =
        (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val PCM16_SCALE = 32_768.0f
        const val MILLIS_PER_SECOND = 1_000L
    }
}
