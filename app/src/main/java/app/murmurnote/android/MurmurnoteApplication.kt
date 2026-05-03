package app.murmurnote.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.murmurnote.android.util.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

const val CHANNEL_PROCESSING = "processing"

@HiltAndroidApp
class MurmurnoteApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var logger: Logger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        createNotificationChannels()
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
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROCESSING,
                    getString(R.string.notif_channel_processing),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
