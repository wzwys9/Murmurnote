package app.murmurnote.android.util

import java.io.File
import java.io.FileWriter

class RollingLogStore(
    private val dir: File,
    private val currentName: String = "runtime.log",
    private val maxSizeBytes: Long = 2L * 1024 * 1024,
    private val maxFiles: Int = 5
) {
    fun currentFile(): File {
        dir.mkdirs()
        return File(dir, currentName)
    }

    fun files(): List<File> {
        val current = currentFile()
        val rotated = (1..maxFiles).map { File(dir, "runtime.$it.log") }
        return listOf(current) + rotated
    }

    fun appendLine(line: String) {
        val current = currentFile()
        rotateIfNeeded(current)
        FileWriter(current, true).use { it.append(line).append('\n') }
    }

    fun clear() {
        files().forEach { runCatching { it.delete() } }
        runCatching { currentFile().createNewFile() }
    }

    private fun rotateIfNeeded(current: File) {
        if (!current.exists() || current.length() <= maxSizeBytes) return
        File(dir, "runtime.$maxFiles.log").delete()
        for (i in maxFiles - 1 downTo 1) {
            val src = File(dir, "runtime.$i.log")
            if (src.exists()) src.renameTo(File(dir, "runtime.${i + 1}.log"))
        }
        current.renameTo(File(dir, "runtime.1.log"))
    }
}
