package app.murmurnote.android.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把日常运行日志同时写到 Logcat + 本地文件。
 * 文件位置：Android/data/app.murmurnote.android/files/logs/runtime.log
 * 自动轮转：>2MB 时备份为 runtime.log.old 后从头写。
 */
@Singleton
class Logger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "Murmurnote"
    private val maxSize = 2L * 1024 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun logFile(): File {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        return File(dir, "runtime.log")
    }

    @Synchronized
    fun line(level: String, scope: String, msg: String, tr: Throwable? = null) {
        val stamp = fmt.format(Date())
        val full = "$stamp $level [$scope] $msg" + (tr?.let { "\n${Log.getStackTraceString(it)}" } ?: "")
        when (level) {
            "E" -> Log.e(tag, "[$scope] $msg", tr)
            "W" -> Log.w(tag, "[$scope] $msg", tr)
            "D" -> Log.d(tag, "[$scope] $msg")
            else -> Log.i(tag, "[$scope] $msg")
        }
        runCatching {
            val f = logFile()
            if (f.exists() && f.length() > maxSize) {
                val old = File(f.parentFile, "runtime.log.old")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            FileWriter(f, true).use { it.append(full).append('\n') }
        }
    }

    fun i(scope: String, msg: String) = line("I", scope, msg)
    fun w(scope: String, msg: String, tr: Throwable? = null) = line("W", scope, msg, tr)
    fun e(scope: String, msg: String, tr: Throwable? = null) = line("E", scope, msg, tr)
    fun d(scope: String, msg: String) = line("D", scope, msg)
}
