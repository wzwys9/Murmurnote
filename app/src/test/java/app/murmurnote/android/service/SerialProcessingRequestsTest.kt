package app.murmurnote.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerialProcessingRequestsTest {

    @Test
    fun newerStartsWaitUntilTheCurrentRequestCompletes() {
        val queue = SerialProcessingRequests<String>()

        queue.enqueue("first", startId = 10)
        assertEquals("first", queue.takeIfIdle())
        queue.enqueue("second", startId = 11)

        assertNull(queue.takeIfIdle())
        queue.complete("first")
        assertEquals("second", queue.takeIfIdle())
        assertEquals(11, queue.latestStartId)
    }

    @Test
    fun cancellingPendingRequestsNeverDropsTheCurrentRequest() {
        val queue = SerialProcessingRequests<String>()
        queue.enqueue("running", startId = 20)
        queue.enqueue("waiting", startId = 21)
        assertEquals("running", queue.takeIfIdle())

        assertEquals(listOf("waiting"), queue.cancelPending())
        assertEquals("running", queue.current)
    }

    @Test(expected = IllegalStateException::class)
    fun aDifferentRequestCannotCompleteTheCurrentOne() {
        val queue = SerialProcessingRequests<String>()
        queue.enqueue("running", startId = 1)
        queue.takeIfIdle()

        queue.complete("other")
    }
}
