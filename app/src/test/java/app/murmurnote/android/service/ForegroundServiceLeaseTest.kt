package app.murmurnote.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceLeaseTest {

    @Test
    fun acquireAndReleaseArePairedAndIdempotent() {
        var starts = 0
        var stops = 0
        val lease = ForegroundServiceLease(
            startService = { starts++ },
            stopService = { stops++ },
        )

        lease.acquire()
        lease.acquire()
        assertTrue(lease.isAcquired)
        assertEquals(1, starts)

        lease.release()
        lease.release()
        assertFalse(lease.isAcquired)
        assertEquals(1, stops)
    }

    @Test
    fun failedStartDoesNotLeakAnAcquiredLeaseAndCanBeRetried() {
        var starts = 0
        var stops = 0
        val lease = ForegroundServiceLease(
            startService = {
                starts++
                if (starts == 1) error("start rejected")
            },
            stopService = { stops++ },
        )

        assertThrows(IllegalStateException::class.java) { lease.acquire() }
        assertFalse(lease.isAcquired)
        assertEquals(0, stops)

        lease.acquire()
        assertTrue(lease.isAcquired)
        assertEquals(2, starts)
    }

    @Test
    fun failedStopKeepsTheLeaseAcquiredSoCleanupCanBeRetried() {
        var stops = 0
        val lease = ForegroundServiceLease(
            startService = {},
            stopService = {
                stops++
                if (stops == 1) error("stop rejected")
            },
        )

        lease.acquire()
        assertThrows(IllegalStateException::class.java) { lease.release() }
        assertTrue(lease.isAcquired)

        lease.release()
        assertFalse(lease.isAcquired)
        assertEquals(2, stops)
    }
}
