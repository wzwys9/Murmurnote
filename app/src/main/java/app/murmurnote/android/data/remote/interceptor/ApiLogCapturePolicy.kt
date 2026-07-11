package app.murmurnote.android.data.remote.interceptor

import java.net.URI
import java.util.Locale

/**
 * Allowlist-based capture policy for diagnostics that can be exported by users.
 *
 * Network bodies are intentionally never captured. URLs retain only the scheme
 * and host so arbitrary query values, fragments, user-info, ports, and paths do
 * not become durable logs.
 */
internal object ApiLogCapturePolicy {
    const val BODY_CAPTURE_ENABLED = false

    private const val INVALID_URL = "<invalid-url>"

    fun sanitizeUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return INVALID_URL
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            return INVALID_URL
        }
        val formattedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        return "$scheme://$formattedHost"
    }

    /** Exception messages can embed response bodies; only stable class metadata is durable. */
    fun errorType(failure: Throwable): String =
        failure.javaClass.simpleName.takeIf(String::isNotBlank)?.take(100) ?: "NetworkError"
}
