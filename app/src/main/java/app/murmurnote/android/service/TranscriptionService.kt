package app.murmurnote.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.murmurnote.android.CHANNEL_PROCESSING
import app.murmurnote.android.MainActivity
import app.murmurnote.android.R
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.PipelineStatusBus
import app.murmurnote.android.domain.pipeline.ProcessingQueueEntry
import app.murmurnote.android.domain.pipeline.ProcessingQueueTracker
import app.murmurnote.android.domain.pipeline.ProcessingQueueStatus
import app.murmurnote.android.domain.pipeline.ProcessingStartupRecovery
import app.murmurnote.android.domain.usecase.ProcessRecordingUseCase
import app.murmurnote.android.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class TranscriptionService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val ACTION_CANCEL_CURRENT = "app.murmurnote.android.action.CANCEL_CURRENT_TRANSCRIPTION"
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

        fun cancelCurrentIntent(ctx: android.content.Context): Intent =
            Intent(ctx, TranscriptionService::class.java)
                .setAction(ACTION_CANCEL_CURRENT)
    }

    @Inject lateinit var processUseCase: ProcessRecordingUseCase
    @Inject lateinit var statusBus: PipelineStatusBus
    @Inject lateinit var queueTracker: ProcessingQueueTracker
    @Inject lateinit var processingStartupRecovery: ProcessingStartupRecovery
    @Inject lateinit var logger: Logger

    // Service lifecycle callbacks and queue transitions are all main-thread confined. The
    // pipeline itself uses flowOn(IO), so collection here does not move media work to main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val requests = SerialProcessingRequests<ProcessingRequest>()
    private var destroyed = false

    private data class ProcessingRequest(
        val queueId: String,
        val file: File,
        val source: RecordingSource,
        val existingRecordingId: String?,
        val startId: Int
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        logger.i("Service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requests.observeStart(startId)
        if (intent?.action == ACTION_CANCEL_CURRENT) {
            startForegroundCompat(buildNotification(getString(R.string.service_cancelling)))
            cancelCurrent(startId)
            return START_NOT_STICKY
        }
        // Android 8+ 强制要求：startForegroundService 之后必须在 ~5s 内调一次 startForeground，
        // 否则 RemoteServiceException。所以"无 file_path 直接 stopSelf"这条路径也要先把
        // 通知挂上去再退出，免得调用方（RecordingController / AudioImporter）走 startForegroundService 的时候被框架杀掉。
        val path = intent?.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            logger.w("Service", "onStartCommand without file_path → start+stop foreground")
            if (!requests.hasWork) {
                startForegroundCompat(buildNotification(getString(R.string.service_invalid_request)))
                stopForegroundSelf()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val sourceName = intent.getStringExtra(EXTRA_SOURCE) ?: RecordingSource.RECORDED.name
        val source = runCatching { RecordingSource.valueOf(sourceName) }.getOrDefault(RecordingSource.RECORDED)
        val existingRecordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        val request = ProcessingRequest(
            queueId = UUID.randomUUID().toString(),
            file = File(path),
            source = source,
            existingRecordingId = existingRecordingId,
            startId = startId
        )
        logger.i("Service", "enqueue source=$source startId=$startId reprocess=${existingRecordingId != null}")

        startForegroundCompat(buildNotification(getString(R.string.service_queued)))
        requests.enqueue(request, startId)
        queueTracker.enqueue(
            ProcessingQueueEntry(
                queueId = request.queueId,
                recordingId = existingRecordingId,
                fileName = request.file.name,
                status = ProcessingQueueStatus.WAITING,
                detail = getString(R.string.service_waiting)
            )
        )
        processNextIfIdle()
        return START_NOT_STICKY
    }

    private fun processNextIfIdle() {
        if (destroyed || job?.isActive == true || requests.current != null) return
        val request = requests.takeIfIdle() ?: run {
            releaseWakeLock()
            stopForegroundSelf()
            stopSelfResult(requests.latestStartId)
            return
        }
        releaseWakeLock()
        acquireWakeLock()
        val initialText = if (request.existingRecordingId != null) {
            getString(R.string.service_reprocessing)
        } else {
            getString(R.string.service_preparing)
        }
        queueTracker.markRunning(request.queueId, initialText)
        updateNotification(initialText)
        job = scope.launch {
            try {
                processingStartupRecovery.awaitCompletion()
                processUseCase(request.file, request.source, request.existingRecordingId).collect { stage ->
                    statusBus.update(stage)
                    val text = labelOf(stage)
                    queueTracker.updateDetail(request.queueId, text)
                    updateNotification(text)
                    logger.i("Service", "stage → $text")
                    if (stage is PipelineStage.Completed || stage is PipelineStage.Failed) {
                        if (stage is PipelineStage.Completed) {
                            queueTracker.markCompleted(request.queueId)
                        } else if (stage is PipelineStage.Failed) {
                            queueTracker.markFailed(
                                request.queueId,
                                getString(R.string.service_processing_failed_retry)
                            )
                        }
                    }
                }
            } catch (t: CancellationException) {
                logger.w("Service", "pipeline cancelled")
            } catch (t: Throwable) {
                logger.e("Service", "pipeline crashed", t)
                val error = t.message ?: t.javaClass.simpleName
                statusBus.update(PipelineStage.Failed("service", error))
                queueTracker.markFailed(
                    request.queueId,
                    getString(R.string.service_processing_failed_retry)
                )
            } finally {
                requests.complete(request)
                job = null
                queueTracker.pruneFinished()
                releaseWakeLock()
                processNextIfIdle()
            }
        }
    }

    private fun cancelCurrent(startId: Int) {
        val running = requests.current
        if (running == null) {
            requests.cancelPending().forEach { queueTracker.markCancelled(it.queueId) }
            stopForegroundSelf()
            stopSelfResult(startId)
            return
        }
        logger.w("Service", "cancel current queue=${running.queueId}")
        queueTracker.markCancelled(running.queueId)
        job?.cancel(CancellationException(getString(R.string.service_user_cancelled_retry)))
        statusBus.update(
            PipelineStage.Failed("cancelled", getString(R.string.service_cancelled))
        )
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
        }.onFailure {
            logger.w("Service", "partial wake lock release failed type=${it.javaClass.simpleName}")
        }
        logger.i("Service", "partial wake lock released")
    }

    private fun labelOf(s: PipelineStage): String = when (s) {
        is PipelineStage.Converting -> getString(R.string.service_converting)
        is PipelineStage.Splitting -> if (s.segmentCount == 0) {
            getString(R.string.service_splitting)
        } else {
            getString(R.string.service_split_complete, s.segmentCount)
        }
        is PipelineStage.Transcribing -> getString(
            R.string.service_transcribing,
            s.segmentIndex + 1,
            s.totalSegments,
            s.recognizedChars
        )
        is PipelineStage.Extracting -> getString(R.string.service_extracting, s.transcriptLength)
        is PipelineStage.Saving -> getString(R.string.service_saving)
        is PipelineStage.Completed -> getString(R.string.service_processing_complete)
        is PipelineStage.Failed -> getString(R.string.service_processing_failed)
        else -> getString(R.string.service_processing)
    }

    private fun startForegroundCompat(notif: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun stopForegroundSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        destroyed = true
        val interruptedMessage = getString(R.string.service_interrupted_retry)
        requests.current?.let {
            queueTracker.markFailed(it.queueId, interruptedMessage)
        }
        requests.cancelPending().forEach {
            queueTracker.markFailed(it.queueId, interruptedMessage)
        }
        job?.cancel(CancellationException(interruptedMessage))
        releaseWakeLock()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

}
