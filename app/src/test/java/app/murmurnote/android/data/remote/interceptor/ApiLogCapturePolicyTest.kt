package app.murmurnote.android.data.remote.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiLogCapturePolicyTest {

    @Test
    fun bodyCaptureIsDisabledByDefault() {
        assertFalse(ApiLogCapturePolicy.BODY_CAPTURE_ENABLED)
    }

    @Test
    fun sanitizeUrlKeepsOnlySchemeAndHost() {
        val sanitized = ApiLogCapturePolicy.sanitizeUrl(
            "https://user:password@api.example.com:8443/v1/audio/transcriptions" +
                "?prompt=private%20meeting&api_key=secret#raw-transcript"
        )

        assertEquals("https://api.example.com", sanitized)
    }

    @Test
    fun sanitizeUrlDoesNotEchoMalformedInput() {
        val sanitized = ApiLogCapturePolicy.sanitizeUrl(
            "not a url?transcript=private meeting"
        )

        assertEquals("<invalid-url>", sanitized)
        assertFalse(sanitized.contains("private meeting"))
    }

    @Test
    fun errorTypeNeverIncludesThrowableMessage() {
        val secret = "private transcript and upstream body"

        val captured = ApiLogCapturePolicy.errorType(IllegalStateException(secret))

        assertEquals("IllegalStateException", captured)
        assertFalse(captured.contains(secret))
    }
}
