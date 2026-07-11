package app.murmurnote.android.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsContentPolicyTest {

    @Test
    fun cloudEngineShowsCloudSettings() {
        assertEquals(
            AsrSettingsPanel.CLOUD,
            resolveAsrSettingsPanel("CLOUD_GLM")
        )
    }

    @Test
    fun everyLocalEngineShowsLocalSettings() {
        listOf(
            "LOCAL_SENSE_VOICE",
            "LOCAL_QWEN3_ASR",
            "LOCAL_FIRE_RED_ASR"
        ).forEach { engineType ->
            assertEquals(
                AsrSettingsPanel.LOCAL,
                resolveAsrSettingsPanel(engineType)
            )
        }
    }

    @Test
    fun unknownEngineFallsBackToCloudSettings() {
        assertEquals(
            AsrSettingsPanel.CLOUD,
            resolveAsrSettingsPanel("UNKNOWN")
        )
    }
}
