package app.murmurnote.android.domain.pipeline

import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.di.ApplicationScope
import app.murmurnote.android.util.Logger
import app.murmurnote.android.util.OneShotStartupTask
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ensures stale rows are recovered once before this process creates or resumes work. */
@Singleton
class ProcessingStartupRecovery @Inject constructor(
    recordingRepository: RecordingRepository,
    logger: Logger,
    @ApplicationScope scope: CoroutineScope
) {
    private val task = OneShotStartupTask(scope) {
        withContext(Dispatchers.IO) {
            runCatching { recordingRepository.markInterruptedProcessingFailed() }
                .onSuccess { recovered ->
                    if (recovered > 0) {
                        logger.i(
                            "Recovery",
                            "marked interrupted processing rows failed count=$recovered"
                        )
                    }
                }
                .onFailure { error ->
                    logger.w(
                        "Recovery",
                        "startup recovery failed type=${error.javaClass.simpleName}"
                    )
                }
        }
    }

    fun start() = task.start()

    suspend fun awaitCompletion() = task.await()
}
