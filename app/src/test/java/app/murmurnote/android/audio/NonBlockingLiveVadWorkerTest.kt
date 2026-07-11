package app.murmurnote.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NonBlockingLiveVadWorkerTest {

    @Test
    fun fullQueueDisablesOnlyLivePreviewWithoutBlockingTheCaptureCaller() {
        val firstChunkEntered = CountDownLatch(1)
        val releaseFirstChunk = CountDownLatch(1)
        val processor = object : LivePcmProcessor {
            override fun acceptPcm(pcm16Le: ByteArray) {
                firstChunkEntered.countDown()
                releaseFirstChunk.await(5, TimeUnit.SECONDS)
            }

            override fun finish() = Unit
            override fun close() = Unit
        }
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = 1,
            processorFactory = { processor },
        )
        worker.start()

        assertTrue(worker.tryOffer(byteArrayOf(1, 0)))
        assertTrue(firstChunkEntered.await(1, TimeUnit.SECONDS))
        assertTrue(worker.tryOffer(byteArrayOf(2, 0)))

        val startedAt = System.nanoTime()
        assertFalse(worker.tryOffer(byteArrayOf(3, 0)))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("tryOffer took ${elapsedMs}ms", elapsedMs < 250)
        assertEquals(LiveVadWorkerState.DISABLED_BACKPRESSURE, worker.snapshot().state)
        // The chunk that observed a full queue and the already queued stale preview chunk are both
        // discarded when this recording's preview fails closed.
        assertEquals(2L, worker.snapshot().droppedChunkCount)
        releaseFirstChunk.countDown()
        assertTrue(worker.awaitStopped(1_000))
    }

    @Test
    fun queuedPcmIsDefensivelyCopiedBeforeTheCaptureBufferIsReused() {
        val accepted = mutableListOf<ByteArray>()
        val finished = CountDownLatch(1)
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = 2,
            processorFactory = {
                object : LivePcmProcessor {
                    override fun acceptPcm(pcm16Le: ByteArray) {
                        accepted += pcm16Le
                    }

                    override fun finish() = Unit
                    override fun close() {
                        finished.countDown()
                    }
                }
            },
        )
        worker.start()
        val captureBuffer = byteArrayOf(1, 2, 3, 4, 99, 99)

        assertTrue(worker.tryOffer(captureBuffer, length = 4))
        captureBuffer.fill(0)
        worker.finish()

        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), accepted.single())
        assertEquals(LiveVadWorkerState.STOPPED, worker.snapshot().state)
    }

    @Test
    fun processorFailureFailsClosedAndNeverInvokesAnotherBoundaryStrategy() {
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = 2,
            processorFactory = {
                object : LivePcmProcessor {
                    override fun acceptPcm(pcm16Le: ByteArray) = error("Silero failed")
                    override fun finish() = Unit
                    override fun close() = Unit
                }
            },
        )
        worker.start()

        assertTrue(worker.tryOffer(byteArrayOf(1, 0)))
        assertTrue(worker.awaitState(LiveVadWorkerState.FAILED_NEURAL_VAD, timeoutMs = 1_000))
        assertFalse(worker.tryOffer(byteArrayOf(2, 0)))
        assertEquals("IllegalStateException", worker.snapshot().failureType)
    }

    @Test
    fun gracefulFinishDrainsEveryAcceptedChunkBeforeFlushingAndClosing() {
        val events = mutableListOf<String>()
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = 4,
            processorFactory = {
                object : LivePcmProcessor {
                    override fun acceptPcm(pcm16Le: ByteArray) {
                        synchronized(events) { events += "pcm:${pcm16Le[0]}" }
                    }

                    override fun finish() {
                        synchronized(events) { events += "finish" }
                    }

                    override fun close() {
                        synchronized(events) { events += "close" }
                    }
                }
            },
        )
        worker.start()

        assertTrue(worker.tryOffer(byteArrayOf(1, 0)))
        assertTrue(worker.tryOffer(byteArrayOf(2, 0)))
        worker.finish()

        assertTrue(worker.awaitStopped(1_000))
        assertEquals(listOf("pcm:1", "pcm:2", "finish", "close"), synchronized(events) { events.toList() })
    }

    @Test
    fun concurrentFinishCannotOvertakeAnOfferThatReturnsAccepted() {
        val copyStarted = CountDownLatch(1)
        val allowCopy = CountDownLatch(1)
        val acceptedPcm = mutableListOf<ByteArray>()
        val executor = Executors.newFixedThreadPool(2)
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = 2,
            processorFactory = {
                object : LivePcmProcessor {
                    override fun acceptPcm(pcm16Le: ByteArray) {
                        synchronized(acceptedPcm) { acceptedPcm += pcm16Le }
                    }

                    override fun finish() = Unit
                    override fun close() = Unit
                }
            },
            copyChunk = { buffer, length ->
                copyStarted.countDown()
                check(allowCopy.await(1, TimeUnit.SECONDS))
                buffer.copyOf(length)
            }
        )
        try {
            worker.start()
            assertTrue(worker.awaitState(LiveVadWorkerState.RUNNING, 1_000))
            val offer = executor.submit<Boolean> { worker.tryOffer(byteArrayOf(1, 0)) }
            assertTrue(copyStarted.await(1, TimeUnit.SECONDS))
            val finish = executor.submit { worker.finish() }

            allowCopy.countDown()

            assertTrue(offer.get(1, TimeUnit.SECONDS))
            finish.get(1, TimeUnit.SECONDS)
            assertTrue(worker.awaitStopped(1_000))
            assertEquals(1, synchronized(acceptedPcm) { acceptedPcm.size })
            assertArrayEquals(byteArrayOf(1, 0), synchronized(acceptedPcm) { acceptedPcm.single() })
        } finally {
            worker.abort()
            executor.shutdownNow()
        }
    }
}
