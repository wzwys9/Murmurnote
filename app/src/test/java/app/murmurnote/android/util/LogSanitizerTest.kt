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
}
