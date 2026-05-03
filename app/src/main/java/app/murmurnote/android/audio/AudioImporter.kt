package app.murmurnote.android.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.service.TranscriptionService
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val logger: Logger
) {
    suspend fun importAndProcess(uri: Uri): Result<File> = runCatching {
        val name = queryDisplayName(uri) ?: "imported"
        val sanitized = name.replace(Regex("[^A-Za-z0-9._\\-一-龥]"), "_")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "imports").apply { mkdirs() }
        val target = File(dir, "imp_${ts}_$sanitized")

        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("无法打开 Uri：$uri")
        }
        if (!target.exists() || target.length() < 1024) error("文件过小或拷贝失败")

        logger.i("Import", "uri=$uri → ${target.absolutePath} size=${target.length()}")
        ContextCompat.startForegroundService(
            context,
            TranscriptionService.intent(context, target, RecordingSource.IMPORTED)
        )
        target
    }.onFailure { logger.e("Import", "import failed: $uri", it) }

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
}
