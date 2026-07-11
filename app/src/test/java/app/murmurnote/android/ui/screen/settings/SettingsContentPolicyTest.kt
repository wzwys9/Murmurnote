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
            "已配置 API",
            asrSettingsStatusText(
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
            "当前模型：Qwen3-ASR",
            asrSettingsStatusText(
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
            "已配置 API",
            asrSettingsStatusText(
                panel = AsrSettingsPanel.CLOUD,
                selected = false,
                cloudApiConfigured = true,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
        assertEquals(
            "API未配置",
            asrSettingsStatusText(
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
            asrSettingsStatusText(
                panel = AsrSettingsPanel.LOCAL,
                selected = false,
                cloudApiConfigured = false,
                localModelDisplayName = "SenseVoiceSmall",
            ),
        )
    }
}
