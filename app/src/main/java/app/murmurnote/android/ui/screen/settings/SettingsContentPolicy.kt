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

internal fun asrSettingsStatusText(
    panel: AsrSettingsPanel,
    selected: Boolean,
    cloudApiConfigured: Boolean,
    localModelDisplayName: String,
): String? {
    return when (panel) {
        AsrSettingsPanel.CLOUD -> if (cloudApiConfigured) "已配置 API" else "API未配置"
        AsrSettingsPanel.LOCAL -> if (selected) "当前模型：$localModelDisplayName" else null
    }
}
