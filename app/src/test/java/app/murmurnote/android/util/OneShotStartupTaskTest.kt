package app.murmurnote.android.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OneShotStartupTaskTest {

    @Test
    fun concurrentCallersShareExactlyOneStartupAction() = runBlocking {
        val calls = AtomicInteger()
        val task = OneShotStartupTask(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            action = { calls.incrementAndGet() }
        )

        repeat(20) { task.start() }
        repeat(20) { task.await() }

        assertEquals(1, calls.get())
    }
}
