package app.murmurnote.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmurnote.android.util.Logger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SileroVadStreamingSessionTest {

    @Test
    fun bundledModelAndNativeLibraryRunAsOneStreamingSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = SileroVadDetector(context, Logger(context))
        val detected = mutableListOf<NeuralVadSegmentPlanner.SpeechRange>()

        detector.openStreamingSession().use { session ->
            repeat(40) {
                detected += session.acceptFrame(FloatArray(SileroVadDetector.WINDOW_SIZE_SAMPLES))
            }
            detected += session.flush()
        }

        assertTrue(detected.isEmpty())
    }
}
