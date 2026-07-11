package app.murmurnote.android.data.asr

import java.io.File
import java.io.IOException

internal object ModelDownloadSizePolicy {
    fun accountRead(writtenBytes: Long, incomingBytes: Int, expectedBytes: Long): Long {
        if (writtenBytes < 0L || expectedBytes < 0L || incomingBytes <= 0) {
            throw IOException("模型下载字节计数无效")
        }
        if (writtenBytes > expectedBytes - incomingBytes) {
            throw IOException("远端模型包超过预期大小")
        }
        return writtenBytes + incomingBytes
    }
}

internal object ArchiveExtractionPolicy {

    fun resolveTarget(
        root: File,
        entryName: String,
        expectedTopDirectory: String,
    ): File {
        val normalized = normalizeEntryName(entryName)
        if (expectedTopDirectory.isBlank() ||
            expectedTopDirectory.contains('/') ||
            expectedTopDirectory.contains('\\')
        ) {
            throw IOException("模型包顶层目录配置无效")
        }
        if (normalized != expectedTopDirectory &&
            !normalized.startsWith("$expectedTopDirectory/")
        ) {
            throw IOException("模型包包含预期目录之外的条目")
        }

        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, normalized).canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (target == canonicalRoot || !target.path.startsWith(rootPrefix)) {
            throw IOException("模型包条目越过了解压目录")
        }
        return target
    }

    private fun normalizeEntryName(entryName: String): String {
        if (entryName.isBlank() || entryName.indexOf('\u0000') >= 0 || entryName.contains('\\')) {
            throw IOException("模型包包含无效路径")
        }
        if (entryName.startsWith('/') || WINDOWS_ABSOLUTE.matches(entryName)) {
            throw IOException("模型包包含绝对路径")
        }

        var normalized = entryName
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        normalized = normalized.trimEnd('/')
        val segments = normalized.split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("模型包包含目录穿越路径")
        }
        return normalized
    }

    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
}

internal class ArchiveExtractionBudget(
    private val maxEntries: Int,
    private val maxEntryBytes: Long,
    private val maxTotalBytes: Long,
) {
    private var entryCount = 0
    private var declaredTotal = 0L
    private var currentDeclared: Long? = null
    private var currentExtracted = 0L

    init {
        require(maxEntries > 0)
        require(maxEntryBytes >= 0L)
        require(maxTotalBytes >= 0L)
    }

    fun beginEntry(declaredSize: Long, writesData: Boolean) {
        if (currentDeclared != null) throw IOException("上一个模型包条目尚未完成")
        if (++entryCount > maxEntries) throw IOException("模型包条目数量超过安全上限")
        if (declaredSize < 0L || (!writesData && declaredSize != 0L)) {
            throw IOException("模型包条目大小无效")
        }
        if (declaredSize > maxEntryBytes || declaredTotal > maxTotalBytes - declaredSize) {
            throw IOException("模型包解压大小超过安全上限")
        }
        declaredTotal += declaredSize
        currentDeclared = declaredSize
        currentExtracted = 0L
    }

    fun recordExtractedBytes(byteCount: Int) {
        if (byteCount <= 0) throw IOException("模型包读取长度无效")
        val declared = currentDeclared ?: throw IOException("模型包条目尚未开始")
        if (currentExtracted > declared - byteCount) {
            throw IOException("模型包条目实际大小超过声明值")
        }
        currentExtracted += byteCount
    }

    fun finishEntry() {
        val declared = currentDeclared ?: throw IOException("模型包条目尚未开始")
        if (currentExtracted != declared) throw IOException("模型包条目不完整")
        currentDeclared = null
        currentExtracted = 0L
    }
}
