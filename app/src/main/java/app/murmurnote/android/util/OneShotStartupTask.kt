package app.murmurnote.android.util

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class OneShotStartupTask(
    private val scope: CoroutineScope,
    private val action: suspend () -> Unit
) {
    private val started = AtomicBoolean(false)
    private val completion = CompletableDeferred<Unit>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                action()
                completion.complete(Unit)
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                throw failure
            }
        }
    }

    suspend fun await() {
        start()
        completion.await()
    }
}
