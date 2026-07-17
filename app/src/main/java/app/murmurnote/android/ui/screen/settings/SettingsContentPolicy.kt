package app.murmurnote.android.ui.screen.settings

import app.murmurnote.android.data.asr.AsrEngineType

internal enum class AsrSettingsPanel {
    CLOUD,
    LOCAL
}

internal fun resolveAsrSettingsPanel(engineType: String): AsrSettingsPanel =
    if (AsrEngineType.parse(engineType).isLocal()) {
        AsrSettingsPanel.LOCAL
    } else {
        AsrSettingsPanel.CLOUD
    }

internal sealed interface AsrSettingsStatus {
    data class Cloud(val configured: Boolean) : AsrSettingsStatus
    data class Local(val modelDisplayName: String) : AsrSettingsStatus
}

internal fun asrSettingsStatus(
    panel: AsrSettingsPanel,
    selected: Boolean,
    cloudApiConfigured: Boolean,
    localModelDisplayName: String,
): AsrSettingsStatus? {
    return when (panel) {
        AsrSettingsPanel.CLOUD -> AsrSettingsStatus.Cloud(cloudApiConfigured)
        AsrSettingsPanel.LOCAL -> if (selected) {
            AsrSettingsStatus.Local(localModelDisplayName)
        } else {
            null
        }
    }
}
