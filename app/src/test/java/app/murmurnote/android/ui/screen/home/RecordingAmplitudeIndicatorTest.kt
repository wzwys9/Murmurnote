package app.murmurnote.android.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAmplitudeIndicatorTest {

    @Test
    fun amplitudeLevelClampsNoiseFloorAndPeakToAnimationRange() {
        assertEquals(0f, recordingAmplitudeLevel(0), 0f)
        assertEquals(0f, recordingAmplitudeLevel(35), 0f)
        assertEquals(27f / 55f, recordingAmplitudeLevel(62), 0.0001f)
        assertEquals(1f, recordingAmplitudeLevel(90), 0f)
        assertEquals(1f, recordingAmplitudeLevel(100), 0f)
    }

    @Test
    fun livePreviewProgressStopsWhileRecordingIsPaused() {
        assertTrue(shouldAnimateLivePreview(active = true, isPaused = false))
        assertFalse(shouldAnimateLivePreview(active = true, isPaused = true))
        assertFalse(shouldAnimateLivePreview(active = false, isPaused = false))
    }

    @Test
    fun recordingControlCenterUsesTheUpperGoldenSectionOfThePage() {
        val controlTop = upperGoldenSectionTop(
            containerHeight = 1_000f,
            elementHeight = 160f,
        )

        assertEquals(381.966f, controlTop + 80f, 0.001f)
    }
}
