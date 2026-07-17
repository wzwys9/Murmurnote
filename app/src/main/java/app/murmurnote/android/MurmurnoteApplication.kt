package app.murmurnote.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.murmurnote.android.service.AudioRetentionWorker
import app.murmurnote.android.domain.pipeline.ProcessingStartupRecovery
import app.murmurnote.android.util.DiagnosticPrivacyUpgrade
import app.murmurnote.android.util.Logger
import app.murmurnote.android.util.localizedString
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val CHANNEL_PROCESSING = "processing"
const val CHANNEL_RECORDING = "recording"

@HiltAndroidApp
class MurmurnoteApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logger: Logger
    @Inject lateinit var processingStartupRecovery: ProcessingStartupRecovery

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        createNotificationChannels()
        runBackgroundStartupTasks()
    }

    private fun runBackgroundStartupTasks() {
        applicationScope.launch {
            // Earlier builds could persist transcript/response excerpts in runtime.log. Clear
            // those files exactly once before background startup starts writing fresh logs.
            runCatching {
                DiagnosticPrivacyUpgrade.apply(filesDir) { logger.clear() }
            }.onFailure { error ->
                logger.w(
                    "PrivacyUpgrade",
                    "diagnostic privacy upgrade failed type=${error.javaClass.simpleName}"
                )
            }

            // Every producer and the service await this same one-shot task before starting new
            // work, so recovery can never mistake current-process work for stale work.
            processingStartupRecovery.awaitCompletion()

            runCatching {
                AudioRetentionWorker.scheduleDaily(this@MurmurnoteApplication)
            }.onFailure { error ->
                logger.w(
                    "AudioRetention",
                    "daily scheduling failed type=${error.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * 把进程内任何线程的未捕获异常落到 runtime.log 之后，再委派给系统默认 handler。
     * 不自行重启 Activity —— 让用户能感知到崩溃，避免严重问题被静默吞掉。
     * 写日志包在 runCatching 里：即便日志失败也不阻断 previous handler。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { logger.e("Crash", "uncaught on thread=${thread.name}", throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROCESSING,
                localizedString(R.string.notif_channel_processing),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                localizedString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localizedString(R.string.notif_channel_recording_description)
                setSound(null, null)
            }
        )
    }
}
