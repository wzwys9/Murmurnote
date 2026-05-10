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

    fun throwable(value: Throwable): String =
        message(android.util.Log.getStackTraceString(value), limit = 12_000)

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
}
