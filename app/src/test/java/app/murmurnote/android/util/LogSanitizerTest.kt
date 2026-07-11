package app.murmurnote.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun redactsCredentialsFromMessages() {
        val sanitized = LogSanitizer.message(
            "Authorization: Bearer sk-secret\nurl=https://api.example.com/chat?api_key=abc&x=1 token=hidden"
        )

        assertTrue(sanitized.contains("Authorization: <redacted>"))
        assertTrue(sanitized.contains("api_key=<redacted>"))
        assertTrue(sanitized.contains("token=<redacted>"))
        assertFalse(sanitized.contains("sk-secret"))
        assertFalse(sanitized.contains("abc&x=1"))
        assertFalse(sanitized.contains("hidden"))
    }

    @Test
    fun redactsPrivateAppPathsAndTruncatesLongBodies() {
        val sanitized = LogSanitizer.body(
            "/data/user/0/app.murmurnote.android/files/logs/runtime.log " + "x".repeat(100),
            limit = 40
        ).orEmpty()

        assertTrue(sanitized.contains("<app-private-path>"))
        assertTrue(sanitized.contains("<truncated"))
        assertFalse(sanitized.contains("/data/user/0/app.murmurnote.android"))
    }

    @Test
    fun throwableDiagnosticsKeepStackMetadataButDropMessages() {
        val cause = IllegalArgumentException("raw response contained a private transcript")
        val error = IllegalStateException("user prompt and meeting notes", cause)

        val sanitized = LogSanitizer.throwable(error)

        assertTrue(sanitized.contains("IllegalStateException"))
        assertTrue(sanitized.contains("IllegalArgumentException"))
        assertFalse(sanitized.contains("private transcript"))
        assertFalse(sanitized.contains("user prompt"))
        assertFalse(sanitized.contains("meeting notes"))
    }

    @Test
    fun logcatPayloadNeverIncludesRawThrowableMessages() {
        val privateBody = "upstream response echoed a private meeting transcript"
        val error = IllegalStateException(privateBody)

        val payload = LogSanitizer.logcatPayload(
            message = "request failed",
            fields = " status=500",
            throwable = error
        )

        assertTrue(payload.contains("request failed status=500"))
        assertTrue(payload.contains("IllegalStateException"))
        assertFalse(payload.contains(privateBody))
    }
}
