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
