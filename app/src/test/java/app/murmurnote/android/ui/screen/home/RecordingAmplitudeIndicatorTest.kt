package app.murmurnote.android.ui.screen.home

import org.junit.Assert.assertEquals
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
}
