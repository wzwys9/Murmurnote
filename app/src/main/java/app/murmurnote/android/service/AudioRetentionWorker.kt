package app.murmurnote.android.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@HiltWorker
class AudioRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val logger: Logger
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        return try {
            val report = recordingRepository.expireAudio()
            logger.i(
                "AudioRetention",
                "daily audio retention finished",
                fields = mapOf(
                    "eligible" to report.eligibleRecordings,
                    "expired" to report.expiredRecordings,
                    "fileFailures" to report.fileFailureRecordings,
                    "databaseFailures" to report.databaseFailureRecordings,
                    "deletedPaths" to report.deletedPaths,
                    "missingPaths" to report.missingPaths,
                    "retainedSharedPaths" to report.retainedSharedPaths
                )
            )
            if (report.requiresRetry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Exception messages may contain file paths. Log only the failure type.
            logger.w(
                "AudioRetention",
                "daily audio retention failed",
                fields = mapOf("errorType" to error.javaClass.simpleName)
            )
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "daily-audio-retention"

        fun scheduleDaily(context: Context) {
            val request = PeriodicWorkRequestBuilder<AudioRetentionWorker>(1, TimeUnit.DAYS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
