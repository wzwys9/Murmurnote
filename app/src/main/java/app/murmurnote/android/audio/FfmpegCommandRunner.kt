package app.murmurnote.android.audio

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun interface CancellableCommand {
    fun cancel()
}

internal suspend fun <T> awaitCancellableCommand(
    start: (complete: (Result<T>) -> Unit) -> CancellableCommand,
): T = suspendCancellableCoroutine { continuation ->
    val cancellationRequested = AtomicBoolean(false)
    val terminal = AtomicBoolean(false)
    val command = AtomicReference<CancellableCommand?>()

    continuation.invokeOnCancellation {
        cancellationRequested.set(true)
        terminal.compareAndSet(false, true)
        command.getAndSet(null)?.cancel()
    }

    val started = try {
        start { result ->
            if (terminal.compareAndSet(false, true)) continuation.resumeWith(result)
        }
    } catch (error: Throwable) {
        if (terminal.compareAndSet(false, true)) {
            continuation.resumeWith(Result.failure(error))
        }
        return@suspendCancellableCoroutine
    }
    command.set(started)
    if (cancellationRequested.get()) command.getAndSet(null)?.cancel()
}

internal data class FfmpegCommandResult(
    val isSuccess: Boolean,
    val returnCode: String,
)

@Singleton
class FfmpegCommandRunner @Inject constructor() {
    internal suspend fun execute(command: String): FfmpegCommandResult =
        awaitCancellableCommand { complete ->
            val session = FFmpegKit.executeAsync(command) { completed ->
                val returnCode = completed.returnCode
                complete(
                    Result.success(
                        FfmpegCommandResult(
                            isSuccess = ReturnCode.isSuccess(returnCode),
                            returnCode = returnCode?.toString() ?: "unknown",
                        ),
                    ),
                )
            }
            CancellableCommand { FFmpegKit.cancel(session.sessionId) }
        }
}
