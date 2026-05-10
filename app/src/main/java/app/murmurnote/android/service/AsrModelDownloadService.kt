package app.murmurnote.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.murmurnote.android.CHANNEL_PROCESSING
import app.murmurnote.android.MainActivity
import app.murmurnote.android.R
import app.murmurnote.android.data.asr.AsrModelManager
import app.murmurnote.android.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 把模型下载放到前台服务，避免系统在后台 Doze / 内存不足时杀掉进程导致下载半途丢失。
 *
 * 一次只跑一个：onStartCommand 收到第二份 Intent 时直接忽略。模型管理器内部本来就是单实例 +
 * 文件锁式（写到 *.downloading），不会真的并发跑。
 */
@AndroidEntryPoint
class AsrModelDownloadService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_START = "app.murmurnote.android.action.ASR_DOWNLOAD_START"
        const val ACTION_INSTALL_UNVERIFIED = "app.murmurnote.android.action.ASR_INSTALL_UNVERIFIED"
        const val ACTION_CANCEL = "app.murmurnote.android.action.ASR_DOWNLOAD_CANCEL"

        fun start(context: Context) {
            val i = Intent(context, AsrModelDownloadService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, AsrModelDownloadService::class.java).setAction(ACTION_CANCEL)
            context.startService(i)
        }

        fun installUnverified(context: Context) {
            val i = Intent(context, AsrModelDownloadService::class.java).setAction(ACTION_INSTALL_UNVERIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    @Inject lateinit var modelManager: AsrModelManager
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        logger.i("AsrDl", "onStartCommand action=$action startId=$startId hasJob=${job?.isActive == true}")

        // 8.0+ 强约束：startForegroundService 后必须 5s 内 startForeground，无论分支如何先挂通知。
        startForegroundCompat(buildNotification("准备下载本地 ASR 模型…", indeterminate = true))

        when (action) {
            ACTION_CANCEL -> {
                modelManager.cancel()
                stopForegroundSelf()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (job?.isActive == true) {
                    logger.w("AsrDl", "已有任务在跑，忽略本次 START")
                    return START_NOT_STICKY
                }
                job = scope.launch {
                    try {
                        // 边下载边把状态映射到通知
                        val collector = launch {
                            modelManager.status.collect { st -> updateNotification(st) }
                        }
                        modelManager.downloadAndInstall()
                            .onFailure { logger.e("AsrDl", "downloadAndInstall failed", it) }
                            .onSuccess { logger.i("AsrDl", "downloadAndInstall success") }
                        collector.cancel()
                    } finally {
                        stopForegroundSelf()
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_INSTALL_UNVERIFIED -> {
                if (job?.isActive == true) {
                    logger.w("AsrDl", "已有任务在跑，忽略本次 INSTALL_UNVERIFIED")
                    return START_NOT_STICKY
                }
                job = scope.launch {
                    try {
                        val collector = launch {
                            modelManager.status.collect { st -> updateNotification(st) }
                        }
                        modelManager.installDownloadedWithoutHashCheck()
                            .onFailure { logger.e("AsrDl", "install unverified failed", it) }
                            .onSuccess { logger.i("AsrDl", "install unverified success") }
                        collector.cancel()
                    } finally {
                        stopForegroundSelf()
                        stopSelf(startId)
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                stopForegroundSelf()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    private fun updateNotification(st: AsrModelManager.ModelStatus) {
        val (text, indeterminate, progress) = when (st) {
            is AsrModelManager.ModelStatus.Downloading -> {
                val mb = st.bytesPerSec / (1024 * 1024)
                val kb = st.bytesPerSec / 1024
                val speed = if (mb > 0) "${mb}MB/s" else "${kb}KB/s"
                Triple("下载模型 ${(st.progress * 100).toInt()}%（$speed，剩 ${st.etaSec}s）", false, (st.progress * 100).toInt())
            }
            is AsrModelManager.ModelStatus.Extracting ->
                Triple("解压模型 ${(st.progress * 100).toInt()}%", false, (st.progress * 100).toInt())
            is AsrModelManager.ModelStatus.Ready -> Triple("模型已就绪", false, 100)
            is AsrModelManager.ModelStatus.HashMismatch -> Triple("模型校验不匹配，请回到设置页确认", false, 0)
            is AsrModelManager.ModelStatus.Failed -> Triple("下载失败：${st.message.take(40)}", false, 0)
            is AsrModelManager.ModelStatus.Corrupted -> Triple("模型已损坏：${st.reason.take(40)}", false, 0)
            AsrModelManager.ModelStatus.NotDownloaded -> Triple("准备下载…", true, 0)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, indeterminate, progress))
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun stopForegroundSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(text: String, indeterminate: Boolean = false, progress: Int = 0): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AsrModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Murmurnote · 本地 ASR 模型")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "取消", cancelIntent)
        if (indeterminate) builder.setProgress(0, 0, true)
        else if (progress in 0..100) builder.setProgress(100, progress, false)
        return builder.build()
    }

    override fun onDestroy() {
        logger.i("AsrDl", "onDestroy")
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
