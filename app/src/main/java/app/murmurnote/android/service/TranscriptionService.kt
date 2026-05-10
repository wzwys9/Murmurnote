package app.murmurnote.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.murmurnote.android.CHANNEL_PROCESSING
import app.murmurnote.android.MainActivity
import app.murmurnote.android.R
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.PipelineStatusBus
import app.murmurnote.android.domain.usecase.ProcessRecordingUseCase
import app.murmurnote.android.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RECORDING_ID = "recording_id"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000

        fun intent(ctx: android.content.Context, file: File, source: RecordingSource): Intent =
            Intent(ctx, TranscriptionService::class.java)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(EXTRA_SOURCE, source.name)

        /** 重跑入口：复用现有 Recording 行，避免列表多出一条。 */
        fun reprocessIntent(
            ctx: android.content.Context,
            file: File,
            source: RecordingSource,
            existingRecordingId: String
        ): Intent =
            Intent(ctx, TranscriptionService::class.java)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_RECORDING_ID, existingRecordingId)
    }

    @Inject lateinit var processUseCase: ProcessRecordingUseCase
    @Inject lateinit var statusBus: PipelineStatusBus
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        logger.i("Service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ 强制要求：startForegroundService 之后必须在 ~5s 内调一次 startForeground，
        // 否则 RemoteServiceException。所以"无 file_path 直接 stopSelf"这条路径也要先把
        // 通知挂上去再退出，免得调用方（RecordingController / AudioImporter）走 startForegroundService 的时候被框架杀掉。
        val path = intent?.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            logger.w("Service", "onStartCommand without file_path → start+stop foreground")
            startForegroundCompat(buildNotification("无效的处理请求…"))
            stopForegroundSelf()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val sourceName = intent.getStringExtra(EXTRA_SOURCE) ?: RecordingSource.RECORDED.name
        val source = runCatching { RecordingSource.valueOf(sourceName) }.getOrDefault(RecordingSource.RECORDED)
        val existingRecordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        logger.i("Service", "onStartCommand path=$path source=$source startId=$startId reprocess=${existingRecordingId != null}")

        startForegroundCompat(buildNotification(if (existingRecordingId != null) "重新处理录音…" else "正在准备处理…"))

        job?.cancel()
        releaseWakeLock()
        acquireWakeLock()
        job = scope.launch {
            try {
                processUseCase(File(path), source, existingRecordingId).collect { stage ->
                    statusBus.update(stage)
                    val text = labelOf(stage)
                    updateNotification(text)
                    logger.i("Service", "stage → $text")
                    if (stage is PipelineStage.Completed || stage is PipelineStage.Failed) {
                        logger.i("Service", "stopForeground (final stage reached)")
                        releaseWakeLock()
                        stopForegroundSelf()
                        stopSelf(startId)
                    }
                }
            } catch (t: Throwable) {
                logger.e("Service", "pipeline crashed", t)
                statusBus.update(PipelineStage.Failed("service", t.message ?: t.javaClass.simpleName))
                releaseWakeLock()
                stopForegroundSelf()
                stopSelf(startId)
            } finally {
                releaseWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Murmurnote:Transcription")
        wl.setReferenceCounted(false)
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = wl
        logger.i("Service", "partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        wakeLock = null
        runCatching {
            if (wl.isHeld) wl.release()
        }.onFailure { logger.w("Service", "partial wake lock release failed: ${it.message}") }
        logger.i("Service", "partial wake lock released")
    }

    private fun labelOf(s: PipelineStage): String = when (s) {
        is PipelineStage.Converting -> "转码为 mono WAV…"
        is PipelineStage.Splitting -> if (s.segmentCount == 0) "按静音切片中…"
            else "切片完成（${s.segmentCount} 段）"
        is PipelineStage.Transcribing -> "转写第 ${s.segmentIndex + 1}/${s.totalSegments} 段（已识别 ${s.partialText.length} 字）"
        is PipelineStage.Extracting -> "AI 提取中（${s.transcriptLength} 字）…"
        is PipelineStage.Saving -> "入库中…"
        is PipelineStage.Completed -> "处理完成"
        is PipelineStage.Failed -> "失败：${s.errorMessage.take(40)}"
        else -> "处理中…"
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

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.notif_processing_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        logger.i("Service", "onDestroy")
        job?.cancel()
        releaseWakeLock()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

}
