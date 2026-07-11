package app.murmurnote.android.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableCommandTest {

    @Test
    fun completionResumesWithTheNativeResult() = runBlocking {
        val result = awaitCancellableCommand<String> { complete ->
            complete(Result.success("done"))
            TestCommand()
        }

        assertEquals("done", result)
    }

    @Test
    fun coroutineCancellationCancelsTheNativeCommandEvenAcrossStartRace() = runBlocking {
        val startEntered = CompletableDeferred<Unit>()
        val allowHandleReturn = CompletableDeferred<Unit>()
        val command = TestCommand()

        val job = launch(Dispatchers.Default) {
            awaitCancellableCommand<Unit> {
                startEntered.complete(Unit)
                runBlocking { allowHandleReturn.await() }
                command
            }
        }
        startEntered.await()
        job.cancel()
        allowHandleReturn.complete(Unit)
        job.cancelAndJoin()

        assertTrue(command.cancelled)
    }

    private class TestCommand : CancellableCommand {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }
}
