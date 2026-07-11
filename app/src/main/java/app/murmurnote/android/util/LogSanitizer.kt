package app.murmurnote.android.util

/**
 * Centralized log redaction for diagnostics that may be shared from debug builds.
 *
 * The sanitizer is deliberately conservative: it keeps enough shape to debug
 * failures while removing credentials, sensitive query values, private app paths,
 * and very large payloads.
 */
object LogSanitizer {
    private const val DEFAULT_LIMIT = 4_000

    private val bearerRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val assignmentRegex = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|password|secret)\b(\s*[:=]\s*)["']?[^"',&\s}]+"""
    )
    private val queryRegex = Regex("""(?i)([?&](?:api[_-]?key|access[_-]?token|token|key|secret|password)=)[^&#\s]+""")
    private val authHeaderRegex = Regex("""(?i)(Authorization\s*:\s*)[^\r\n]+""")
    private val privatePathRegex = Regex(
        """/(?:data/user/\d+|data/data|storage/emulated/\d+/Android/data)/app\.murmurnote\.android/[^\s,)"']+"""
    )

    fun message(value: String, limit: Int = DEFAULT_LIMIT): String =
        truncate(redact(value), limit)

    /**
     * Stack metadata without exception messages. Network/parser exceptions frequently embed
     * response bodies, prompts, or transcript excerpts in [Throwable.message].
     */
    fun throwable(value: Throwable): String = buildString {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = value
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH && seen.add(current)) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name)
            current.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
                append("\n  at ")
                append(frame.className)
                append('.')
                append(frame.methodName)
                append('(')
                append(frame.fileName ?: "Unknown Source")
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                append(')')
            }
            current = current.cause
            depth++
        }
    }.let { truncate(it, 12_000) }

    /**
     * Builds the complete Logcat payload without handing Android the original [Throwable].
     * Passing that object to Log.e/Log.w would make Logcat render its unsanitized message even
     * though the durable runtime log contains only [throwable] metadata.
     */
    fun logcatPayload(
        message: String,
        fields: String,
        throwable: Throwable?
    ): String = buildString {
        append(message(message))
        append(message(fields))
        throwable?.let {
            append('\n')
            append(this@LogSanitizer.throwable(it))
        }
    }

    fun fieldValue(value: Any?, limit: Int = 1_000): String = when (value) {
        null -> "null"
        is Throwable -> throwable(value)
        else -> message(value.toString(), limit)
    }

    fun body(value: String?, limit: Int = 8_000): String? =
        value?.let { message(it, limit) }

    private fun redact(raw: String): String =
        raw.replace(bearerRegex, "Bearer <redacted>")
            .replace(authHeaderRegex, "$1<redacted>")
            .replace(assignmentRegex) { m ->
                val name = m.groupValues[1]
                val separator = m.groupValues[2]
                "$name${separator}<redacted>"
            }
            .replace(queryRegex, "$1<redacted>")
            .replace(privatePathRegex, "<app-private-path>")

    private fun truncate(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take(limit) + "...<truncated ${value.length - limit} chars>"

    private const val MAX_CAUSE_DEPTH = 4
    private const val MAX_STACK_FRAMES = 24
}
