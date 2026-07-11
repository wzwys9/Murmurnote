package app.murmurnote.android.data.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenAsrPoolCoordinatorTest {

    @Test
    fun `different fingerprint waits and retires old pool only after active lease returns`() =
        runBlocking {
            val retired = mutableListOf<String>()
            val coordinator = FrozenAsrPoolCoordinator<String> { retired.add(it) }
            val oldLease = coordinator.acquire(key("old")) { "old-pool" }
            val switchStarted = CompletableDeferred<Unit>()

            val newLeaseDeferred = async {
                switchStarted.complete(Unit)
                coordinator.acquire(key("new")) { "new-pool" }
            }
            switchStarted.await()
            yield()

            assertFalse(newLeaseDeferred.isCompleted)
            assertTrue(retired.isEmpty())

            coordinator.release(oldLease)
            val newLease = withTimeout(1_000) { newLeaseDeferred.await() }

            assertEquals(listOf("old-pool"), retired)
            coordinator.release(newLease)
            coordinator.retireCurrent()
            assertEquals(listOf("old-pool", "new-pool"), retired)
        }

    @Test
    fun `same fingerprint shares pool and explicit retirement waits for every lease`() = runBlocking {
        val retired = mutableListOf<String>()
        val coordinator = FrozenAsrPoolCoordinator<String> { retired.add(it) }
        val first = coordinator.acquire(key("same", concurrency = 3)) { "shared-pool" }
        val second = coordinator.acquire(key("same", concurrency = 3)) { "unexpected" }

        assertSame(first.payload, second.payload)
        coordinator.retireCurrent()
        assertTrue(retired.isEmpty())

        coordinator.release(first)
        assertTrue(retired.isEmpty())

        coordinator.release(second)
        assertEquals(listOf("shared-pool"), retired)
    }

    @Test
    fun `concurrency change is a pool switch even when fingerprint matches`() = runBlocking {
        val retired = mutableListOf<String>()
        val coordinator = FrozenAsrPoolCoordinator<String> { retired.add(it) }
        val first = coordinator.acquire(key("same", concurrency = 1)) { "one" }
        coordinator.release(first)

        val second = coordinator.acquire(key("same", concurrency = 3)) { "three" }

        assertEquals(listOf("one"), retired)
        assertEquals("three", second.payload)
        coordinator.release(second)
    }

    private fun key(
        fingerprint: String,
        concurrency: Int = 1
    ) = FrozenAsrPoolCoordinator.Key(
        configFingerprint = fingerprint,
        concurrency = concurrency
    )
}
