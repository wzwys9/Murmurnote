package app.murmurnote.android.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把日常运行日志同时写到 Logcat + 私有本地文件。
 * 文件位置：filesDir/logs/runtime.log
 * 自动轮转：单文件超过 2MiB 时保留 runtime.1.log..runtime.5.log。
 */
@Singleton
class Logger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "Murmurnote"
    private val maxSize = 2L * 1024 * 1024
    private val maxFiles = 5
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val store by lazy {
        RollingLogStore(
            dir = File(context.filesDir, "logs"),
            maxSizeBytes = maxSize,
            maxFiles = maxFiles
        )
    }

    fun logFile(): File = store.currentFile()

    fun logFiles(): List<File> = store.files()

    @Synchronized
    fun line(
        level: String,
        scope: String,
        msg: String,
        tr: Throwable? = null,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val stamp = fmt.format(Date())
        val cleanMsg = LogSanitizer.message(msg)
        val fieldText = fields.entries
            .filter { it.key.isNotBlank() }
            .joinToString(separator = " ") { (key, value) -> "$key=${LogSanitizer.fieldValue(value)}" }
            .let { if (it.isBlank()) "" else " $it" }
        val cleanTrace = tr?.let { "\n${LogSanitizer.throwable(it)}" }.orEmpty()
        val thread = Thread.currentThread().name
        val full = "$stamp $level [$scope] ($thread) $cleanMsg$fieldText$cleanTrace"
        when (level) {
            "E" -> Log.e(tag, "[$scope] $cleanMsg$fieldText", tr)
            "W" -> Log.w(tag, "[$scope] $cleanMsg$fieldText", tr)
            "D" -> Log.d(tag, "[$scope] $cleanMsg$fieldText")
            else -> Log.i(tag, "[$scope] $cleanMsg$fieldText")
        }
        runCatching {
            store.appendLine(full)
        }
    }

    @Synchronized
    fun clear() = store.clear()

    fun i(scope: String, msg: String) = line("I", scope, msg)
    fun i(scope: String, msg: String, fields: Map<String, Any?>) = line("I", scope, msg, fields = fields)
    fun w(scope: String, msg: String, tr: Throwable? = null) = line("W", scope, msg, tr)
    fun w(scope: String, msg: String, fields: Map<String, Any?>, tr: Throwable? = null) = line("W", scope, msg, tr, fields)
    fun e(scope: String, msg: String, tr: Throwable? = null) = line("E", scope, msg, tr)
    fun e(scope: String, msg: String, fields: Map<String, Any?>, tr: Throwable? = null) = line("E", scope, msg, tr, fields)
    fun d(scope: String, msg: String) = line("D", scope, msg)
    fun d(scope: String, msg: String, fields: Map<String, Any?>) = line("D", scope, msg, fields = fields)

}
