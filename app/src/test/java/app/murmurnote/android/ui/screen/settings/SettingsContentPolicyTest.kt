package app.murmurnote.android.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun selectedCloudEntryShowsConfiguredApiStatus() {
        assertEquals(
            AsrSettingsStatus.Cloud(configured = true),
            asrSettingsStatus(
                panel = AsrSettingsPanel.CLOUD,
                selected = true,
                cloudApiConfigured = true,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
    }

    @Test
    fun selectedLocalEntryShowsCurrentModel() {
        assertEquals(
            AsrSettingsStatus.Local(modelDisplayName = "Qwen3-ASR"),
            asrSettingsStatus(
                panel = AsrSettingsPanel.LOCAL,
                selected = true,
                cloudApiConfigured = true,
                localModelDisplayName = "Qwen3-ASR",
            ),
        )
    }

    @Test
    fun cloudEntryAlwaysShowsItsApiConfigurationStatus() {
        assertEquals(
            AsrSettingsStatus.Cloud(configured = true),
            asrSettingsStatus(
                panel = AsrSettingsPanel.CLOUD,
                selected = false,
                cloudApiConfigured = true,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
        assertEquals(
            AsrSettingsStatus.Cloud(configured = false),
            asrSettingsStatus(
                panel = AsrSettingsPanel.CLOUD,
                selected = true,
                cloudApiConfigured = false,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
    }

    @Test
    fun inactiveLocalEntryDoesNotClaimItsSavedModelIsActive() {
        assertNull(
            asrSettingsStatus(
                panel = AsrSettingsPanel.LOCAL,
                selected = false,
                cloudApiConfigured = false,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
    }
}
