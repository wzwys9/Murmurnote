package app.murmurnote.android.audio

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal interface LivePcmProcessor : Closeable {
    fun acceptPcm(pcm16Le: ByteArray)
    fun finish()
}

internal enum class LiveVadWorkerState {
    NEW,
    STARTING,
    RUNNING,
    FINISHING,
    STOPPED,
    DISABLED_BACKPRESSURE,
    FAILED_NEURAL_VAD,
    ABORTED,
}

internal data class LiveVadWorkerSnapshot(
    val state: LiveVadWorkerState,
    val droppedChunkCount: Long = 0,
    val failureType: String? = null,
)

/**
 * Bounded, non-blocking ingress between AudioRecord and neural inference. Queue overflow disables
 * live preview for this recording. It never waits, retries with an unbounded buffer, or selects a
 * different boundary detector; the lossless full-recording writer remains outside this worker.
 */
internal class NonBlockingLiveVadWorker(
    queueCapacity: Int,
    private val processorFactory: () -> LivePcmProcessor,
    private val threadName: String = "MurmurnoteLiveSileroVad",
    private val copyChunk: (ByteArray, Int) -> ByteArray = { buffer, length ->
        buffer.copyOf(length)
    },
) {
    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private val stateLock = Any()
    @Volatile private var currentSnapshot = LiveVadWorkerSnapshot(LiveVadWorkerState.NEW)
    @Volatile private var finishRequested = false
    @Volatile private var workerThread: Thread? = null

    init {
        require(queueCapacity > 0) { "Live VAD queue capacity must be positive" }
    }

    fun start() {
        synchronized(stateLock) {
            check(currentSnapshot.state == LiveVadWorkerState.NEW) { "Live VAD worker already started" }
            currentSnapshot = currentSnapshot.copy(state = LiveVadWorkerState.STARTING)
            workerThread = Thread(::runWorker, threadName).also { it.start() }
        }
    }

    fun tryOffer(buffer: ByteArray, length: Int = buffer.size): Boolean {
        require(length >= 0 && length <= buffer.size) { "PCM length is outside the capture buffer" }
        require(length % PCM16_BYTES_PER_SAMPLE == 0) { "PCM16 input must contain complete samples" }
        synchronized(stateLock) {
            if (!isAccepting(currentSnapshot.state)) return false
            if (length == 0) return true

            val copy = copyChunk(buffer, length)
            if (queue.offer(copy)) return true

            disableForBackpressureLocked(droppedItems = 1L)
            return false
        }
    }

    /** Disables preview when a bounded downstream queue (for example live ASR) is full. */
    fun disableForBackpressure(droppedItems: Long = 1L) {
        require(droppedItems >= 0L) { "Dropped item count cannot be negative" }
        synchronized(stateLock) {
            if (isTerminal(currentSnapshot.state)) return
            disableForBackpressureLocked(droppedItems)
        }
    }

    /** Stops accepting input and lets the worker drain every chunk that was successfully offered. */
    fun finish() {
        synchronized(stateLock) {
            if (!isAccepting(currentSnapshot.state)) return
            finishRequested = true
            currentSnapshot = currentSnapshot.copy(state = LiveVadWorkerState.FINISHING)
        }
    }

    fun abort() {
        synchronized(stateLock) {
            if (currentSnapshot.state == LiveVadWorkerState.STOPPED ||
                currentSnapshot.state == LiveVadWorkerState.ABORTED
            ) return
            currentSnapshot = currentSnapshot.copy(state = LiveVadWorkerState.ABORTED)
            queue.clear()
            workerThread?.interrupt()
        }
    }

    fun snapshot(): LiveVadWorkerSnapshot = currentSnapshot

    fun awaitStopped(timeoutMs: Long): Boolean {
        val thread = workerThread ?: return isTerminal(currentSnapshot.state)
        thread.join(timeoutMs.coerceAtLeast(0L))
        return !thread.isAlive
    }

    /** Test/diagnostic helper; production capture never waits on this method. */
    fun awaitState(expected: LiveVadWorkerState, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        do {
            if (currentSnapshot.state == expected) return true
            Thread.sleep(5L)
        } while (System.nanoTime() < deadline)
        return currentSnapshot.state == expected
    }

    private fun runWorker() {
        var processor: LivePcmProcessor? = null
        var completedGracefully = false
        try {
            processor = processorFactory()
            synchronized(stateLock) {
                if (currentSnapshot.state == LiveVadWorkerState.STARTING) {
                    currentSnapshot = currentSnapshot.copy(
                        state = if (finishRequested) {
                            LiveVadWorkerState.FINISHING
                        } else {
                            LiveVadWorkerState.RUNNING
                        },
                    )
                }
            }

            while (!isTerminal(currentSnapshot.state)) {
                val pcm = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (pcm != null) {
                    processor.acceptPcm(pcm)
                    continue
                }
                if (finishRequested) {
                    processor.finish()
                    completedGracefully = true
                    break
                }
            }
        } catch (failure: Throwable) {
            if (currentSnapshot.state != LiveVadWorkerState.ABORTED &&
                currentSnapshot.state != LiveVadWorkerState.DISABLED_BACKPRESSURE
            ) {
                synchronized(stateLock) {
                    currentSnapshot = LiveVadWorkerSnapshot(
                        state = LiveVadWorkerState.FAILED_NEURAL_VAD,
                        droppedChunkCount = currentSnapshot.droppedChunkCount,
                        failureType = failure.javaClass.simpleName,
                    )
                    queue.clear()
                }
            }
        } finally {
            runCatching { processor?.close() }
                .onFailure { closeFailure ->
                    if (!isTerminalFailure(currentSnapshot.state)) {
                        synchronized(stateLock) {
                            currentSnapshot = LiveVadWorkerSnapshot(
                                state = LiveVadWorkerState.FAILED_NEURAL_VAD,
                                droppedChunkCount = currentSnapshot.droppedChunkCount,
                                failureType = closeFailure.javaClass.simpleName,
                            )
                        }
                    }
                }
            if (completedGracefully && currentSnapshot.state == LiveVadWorkerState.FINISHING) {
                synchronized(stateLock) {
                    currentSnapshot = currentSnapshot.copy(state = LiveVadWorkerState.STOPPED)
                }
            }
        }
    }

    private fun isAccepting(state: LiveVadWorkerState): Boolean =
        state == LiveVadWorkerState.STARTING || state == LiveVadWorkerState.RUNNING

    private fun isTerminal(state: LiveVadWorkerState): Boolean = when (state) {
        LiveVadWorkerState.STOPPED,
        LiveVadWorkerState.DISABLED_BACKPRESSURE,
        LiveVadWorkerState.FAILED_NEURAL_VAD,
        LiveVadWorkerState.ABORTED -> true
        else -> false
    }

    private fun isTerminalFailure(state: LiveVadWorkerState): Boolean =
        state == LiveVadWorkerState.DISABLED_BACKPRESSURE ||
            state == LiveVadWorkerState.FAILED_NEURAL_VAD ||
            state == LiveVadWorkerState.ABORTED

    private fun disableForBackpressureLocked(droppedItems: Long) {
        val discarded = mutableListOf<ByteArray>()
        queue.drainTo(discarded)
        currentSnapshot = LiveVadWorkerSnapshot(
            state = LiveVadWorkerState.DISABLED_BACKPRESSURE,
            droppedChunkCount = currentSnapshot.droppedChunkCount +
                discarded.size.toLong() + droppedItems,
        )
    }

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val POLL_INTERVAL_MS = 25L
    }
}
