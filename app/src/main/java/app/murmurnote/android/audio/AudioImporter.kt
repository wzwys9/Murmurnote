package app.murmurnote.android.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.domain.pipeline.ProcessingStartupRecovery
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    suspend fun importAndProcess(uri: Uri): Result<File> {
        var target: File? = null
        var durableRecordingId: String? = null
        return runCatching {
            processingStartupRecovery.awaitCompletion()
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
            // createTempFile is atomic. Two same-name imports in the same second can never share
            // ownership, so deleting or expiring one Recording cannot remove another one's audio.
            val uniqueTarget = File.createTempFile("imp_", suffix, dir).also { target = it }

            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    uniqueTarget.outputStream().use { input.copyTo(it) }
                } ?: error("无法打开所选音频")
            }
            if (!uniqueTarget.exists() || uniqueTarget.length() < 1024) {
                error("文件过小或拷贝失败")
            }

            logger.i("Import", "audio copied size=${uniqueTarget.length()}")
            val now = System.currentTimeMillis()
            val recordingId = UUID.randomUUID().toString()
            recordingRepository.insert(
                Recording(
                    id = recordingId,
                    title = "导入音频",
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
                    "导入文件已保存，但处理服务启动失败，可手动重试"
                )
            }
            logger.w("Import", "import failed type=${error.javaClass.simpleName}")
        }
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
    }
}
