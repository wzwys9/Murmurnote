package app.murmurnote.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecorderCaptureDispatchTest {

    @Test
    fun rejectedLivePreviewNeverPreventsTheLosslessWrite() {
        val captured = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        val buffer = byteArrayOf(1, 2, 3, 4, 99, 99)

        val accepted = AudioRecorder.dispatchCapturedPcm(
            buffer = buffer,
            length = 4,
            writeLossless = { bytes, length ->
                events += "lossless"
                captured += bytes.copyOf(length)
            },
            offerLivePreview = { _, _ ->
                events += "preview"
                false
            }
        )

        assertFalse(accepted)
        assertEquals(listOf("lossless", "preview"), events)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), captured.single())
    }

    @Test
    fun livePreviewExceptionIsReportedOnlyAfterTheLosslessWrite() {
        val events = mutableListOf<String>()
        var previewFailure: Throwable? = null

        val accepted = AudioRecorder.dispatchCapturedPcm(
            buffer = byteArrayOf(1, 0),
            length = 2,
            writeLossless = { _, _ -> events += "lossless" },
            offerLivePreview = { _, _ ->
                events += "preview"
                error("preview failed")
            },
            onLivePreviewFailure = { failure ->
                events += "failure"
                previewFailure = failure
            }
        )

        assertFalse(accepted)
        assertEquals(listOf("lossless", "preview", "failure"), events)
        assertEquals("preview failed", previewFailure?.message)
    }

    @Test
    fun staleCaptureSessionCannotWriteOrOfferAfterAReplacementStarts() {
        val events = mutableListOf<String>()

        val accepted = AudioRecorder.dispatchCapturedPcm(
            buffer = byteArrayOf(1, 0),
            length = 2,
            isSessionActive = { false },
            writeLossless = { _, _ -> events += "lossless" },
            offerLivePreview = { _, _ ->
                events += "preview"
                true
            }
        )

        assertFalse(accepted)
        assertTrue(events.isEmpty())
    }
}
