package app.murmurnote.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.murmurnote.android.CHANNEL_RECORDING
import app.murmurnote.android.MainActivity
import app.murmurnote.android.R
import app.murmurnote.android.util.Logger
import app.murmurnote.android.util.localizedString
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Keeps user-initiated microphone capture alive while the screen is off. */
@AndroidEntryPoint
class RecordingForegroundService : Service() {

    companion object {
        internal const val NOTIFICATION_ID = 1003
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1_000

        fun intent(context: Context): Intent =
            Intent(context, RecordingForegroundService::class.java)
    }

    @Inject lateinit var logger: Logger

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            foregroundType,
        )
        acquireWakeLock()
        logger.i("RecordingService", "microphone foreground session active")
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(localizedString(R.string.notif_recording_title))
            .setContentText(localizedString(R.string.notif_recording_text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Murmurnote:Recording")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        logger.i("RecordingService", "partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        val heldLock = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (heldLock.isHeld) heldLock.release()
        }.onFailure { failure ->
            logger.w(
                "RecordingService",
                "partial wake lock release failed type=${failure.javaClass.simpleName}",
            )
        }
        logger.i("RecordingService", "partial wake lock released")
    }

    override fun onDestroy() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        logger.i("RecordingService", "microphone foreground session stopped")
        super.onDestroy()
    }
}
