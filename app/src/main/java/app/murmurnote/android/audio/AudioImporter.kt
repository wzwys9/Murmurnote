package app.murmurnote.android.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.murmurnote.android.R
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.domain.pipeline.ProcessingStartupRecovery
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.BoundedStreams
import app.murmurnote.android.util.Logger
import app.murmurnote.android.util.SizeLimitExceededException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把外部音频 Uri（来自系统文件选择器 / 分享 Intent / VIEW Intent）拷贝到 APP 私有目录，
 * 然后投递给 TranscriptionService 走同一条 Pipeline。
 *
 * NOTES 第二节：不读 ContentResolver.getType() 分流，所有文件统一走解码路径。
 */
@Singleton
class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: RecordingRepository,
    private val processingStartupRecovery: ProcessingStartupRecovery,
    private val logger: Logger
) {
    private val importMutex = Mutex()

    suspend fun importAndProcess(uri: Uri): Result<File> = importMutex.withLock {
        var target: File? = null
        var durableRecordingId: String? = null
        val result = runCatching {
            processingStartupRecovery.awaitCompletion()
            val uniqueTarget = withContext(Dispatchers.IO) {
                val suffix = queryDisplayName(uri)
                    ?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                    ?.let { ".$it" }
                    ?: ".audio"
                val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
                    "应用私有导入目录不可用"
                }
                val dir = File(externalRoot, "imports").apply {
                    check(isDirectory || mkdirs()) { "无法创建导入目录" }
                }
                // createTempFile is atomic. Every Recording exclusively owns its source file.
                val imported = File.createTempFile("imp_", suffix, dir).also { target = it }
                val copyContext = currentCoroutineContext()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    imported.outputStream().use { output ->
                        try {
                            BoundedStreams.copy(
                                input = input,
                                output = output,
                                maxBytes = MAX_IMPORT_BYTES,
                                onChunkCopied = { copyContext.ensureActive() },
                            )
                        } catch (error: SizeLimitExceededException) {
                            throw IllegalArgumentException(
                                "导入音频不能超过 ${MAX_IMPORT_BYTES / (1024 * 1024)} MB",
                                error,
                            )
                        }
                    }
                } ?: error("无法打开所选音频")
                if (!imported.isFile || imported.length() < MIN_IMPORT_BYTES) {
                    error("文件过小或拷贝失败")
                }
                val importDirectoryBytes = dir.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter(File::isFile)
                    .fold(0L) { total, file ->
                        val length = file.length()
                        if (length > MAX_IMPORT_DIRECTORY_BYTES - total) {
                            MAX_IMPORT_DIRECTORY_BYTES + 1L
                        } else {
                            total + length
                        }
                    }
                if (importDirectoryBytes > MAX_IMPORT_DIRECTORY_BYTES) {
                    error("导入目录空间已达安全上限，请先删除不需要的旧录音")
                }
                imported
            }

            logger.i("Import", "audio copied size=${uniqueTarget.length()}")
            val now = System.currentTimeMillis()
            val recordingId = UUID.randomUUID().toString()
            recordingRepository.insert(
                Recording(
                    id = recordingId,
                    title = context.getString(R.string.import_audio_title),
                    originalFilePath = uniqueTarget.absolutePath,
                    durationMs = 0L,
                    createdAt = now,
                    source = RecordingSource.IMPORTED,
                    processingStatus = ProcessingStatus.PENDING,
                    audioExpiresAt = now + AUDIO_RETENTION_MS
                )
            )
            durableRecordingId = recordingId
            ContextCompat.startForegroundService(
                context,
                TranscriptionService.reprocessIntent(
                    context,
                    uniqueTarget,
                    RecordingSource.IMPORTED,
                    recordingId
                )
            )
            uniqueTarget
        }.onFailure { error ->
            val recordingId = durableRecordingId
            if (recordingId == null) {
                target?.delete()
            } else {
                recordingRepository.markProcessingFailedIfInProgress(
                    recordingId,
                    context.getString(R.string.import_audio_saved_processing_failed)
                )
            }
            logger.w("Import", "import failed type=${error.javaClass.simpleName}")
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        result
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** 处理 MainActivity 收到的 SEND / VIEW Intent。 */
    fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
    }

    private companion object {
        const val AUDIO_RETENTION_MS = 30L * 24 * 3600 * 1000
        const val MIN_IMPORT_BYTES = 1024L
        const val MAX_IMPORT_BYTES = 640L * 1024L * 1024L
        const val MAX_IMPORT_DIRECTORY_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
