package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SileroVadDetectorConfigTest {

    @Test
    fun nativeConfigurationMatchesTheFrozenPlannerPreset() {
        val config = SileroVadDetector.nativeConfig()
        val silero = config.sileroVadModelConfig

        assertEquals(SileroVadDetector.MODEL_ASSET_PATH, silero.model)
        assertEquals(NeuralVadSegmentPlanner.PRESET.threshold, silero.threshold, 0.0f)
        assertEquals(NeuralVadSegmentPlanner.PRESET.minSilenceMs / 1_000.0f, silero.minSilenceDuration, 0.0f)
        assertEquals(NeuralVadSegmentPlanner.PRESET.minSpeechMs / 1_000.0f, silero.minSpeechDuration, 0.0f)
        assertEquals(SileroVadDetector.WINDOW_SIZE_SAMPLES, silero.windowSize)
        assertEquals(NeuralVadSegmentPlanner.PRESET.maxSegmentMs / 1_000.0f, silero.maxSpeechDuration, 0.0f)
        assertEquals(SileroVadDetector.SAMPLE_RATE_HZ, config.sampleRate)
        assertEquals(1, config.numThreads)
        assertEquals("cpu", config.provider)
        assertFalse(config.debug)
    }
}
