package app.murmurnote.android.ui.screen.home

import app.murmurnote.android.audio.RecordingController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionUiRestorationTest {

    @Test
    fun activeBackgroundRecordingRestoresControllableUiWithoutRestartingPreview() {
        val initial = HomeViewModel.UiState(
            todayCount = 2,
            totalCount = 7,
            errorMessage = "stale error",
        )
        val session = RecordingController.ActiveSession(
            id = "recording-id",
            file = File("recording.wav"),
            createdAt = 123L,
            isPaused = true,
            elapsedMs = 45_600L,
            amplitudeDb = 68,
        )

        val restored = restoreRecordingUiState(
            initial,
            session,
            liveTranscriptionMessage = "Recording continues in the background",
        )

        assertTrue(restored.isRecording)
        assertTrue(restored.isPaused)
        assertEquals(45_600L, restored.elapsedMs)
        assertEquals(68, restored.amplitudeDb)
        assertEquals(2, restored.todayCount)
        assertEquals(7, restored.totalCount)
        assertNull(restored.errorMessage)
        assertFalse(restored.liveTranscriptionActive)
        assertEquals(
            "Recording continues in the background",
            restored.liveTranscriptionMessage,
        )
    }
}
