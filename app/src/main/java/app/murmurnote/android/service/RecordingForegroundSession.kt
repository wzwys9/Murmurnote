package app.murmurnote.android.service

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Pairs foreground-service start/stop calls and keeps retries safe after a rejected start. */
internal class ForegroundServiceLease(
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
) {
    private val lock = Any()
    private var acquired = false

    val isAcquired: Boolean
        get() = synchronized(lock) { acquired }

    fun acquire() = synchronized(lock) {
        if (acquired) return@synchronized
        startService()
        acquired = true
    }

    fun release() = synchronized(lock) {
        if (!acquired) return@synchronized
        stopService()
        acquired = false
    }
}

/** Starts the microphone foreground service while the user-visible recording session is active. */
@Singleton
class RecordingForegroundSession @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val applicationContext = context.applicationContext
    private val lease = ForegroundServiceLease(
        startService = {
            checkNotNull(
                ContextCompat.startForegroundService(
                    applicationContext,
                    RecordingForegroundService.intent(applicationContext),
                ),
            ) { "系统未能启动录音前台服务" }
        },
        stopService = {
            applicationContext.stopService(RecordingForegroundService.intent(applicationContext))
        },
    )

    val isStarted: Boolean
        get() = lease.isAcquired

    fun start() = lease.acquire()

    fun stop() = lease.release()
}
