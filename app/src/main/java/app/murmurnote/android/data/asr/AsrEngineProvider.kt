package app.murmurnote.android.data.asr

import app.murmurnote.android.data.preference.AppPreferences
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Freezes one typed ASR attempt; local ONNX construction remains lazily provided. */
@Singleton
class AsrEngineProvider @Inject constructor(
    private val appPreferences: AppPreferences,
    private val cloud: CloudAsrEngine,
    private val localProvider: Provider<LocalAsrEngine>
) {

    /**
     * Freezes engine selection and all behavior-affecting settings as one typed attempt. A caller
     * must retain the returned object until every segment has completed; later preference changes
     * intentionally apply only to a future attempt.
     */
    suspend fun snapshotAttempt(
        vadPresetVersion: String,
        locale: Locale
    ): AttemptSelection {
        val runtimeSnapshot = appPreferences.snapshotAsrRuntimePreferences()
        val snapshot = runtimeSnapshot.toAttemptPreferenceSnapshot()
        return when (snapshot.engineType) {
            AsrEngineType.CLOUD_GLM -> {
                val config = CloudAsrSessionConfig.fromBaseUrl(
                    modelId = CloudAsrModels.GLM_ASR_2512,
                    vadPresetVersion = vadPresetVersion,
                    baseUrl = runtimeSnapshot.glmBaseUrl
                )
                if (runtimeSnapshot.glmApiKey.isNotBlank()) {
                    AttemptSelection.Active(
                        engine = cloud,
                        localConfig = null,
                        cloudRequestConfig = CloudAsrRequestConfig(
                            sessionConfig = config,
                            baseUrl = runtimeSnapshot.glmBaseUrl,
                            apiKey = runtimeSnapshot.glmApiKey
                        ),
                        provenance = config.toProvenance()
                    )
                } else {
                    AttemptSelection.NotReady(
                        type = snapshot.engineType,
                        reason = "云端引擎未就绪：请在设置页填写智谱 GLM API Key"
                    )
                }
            }

            AsrEngineType.LOCAL_SENSE_VOICE,
            AsrEngineType.LOCAL_QWEN3_ASR -> {
                val config = AsrAttemptConfigResolver.resolve(
                    preferenceSnapshot = snapshot,
                    vadPresetVersion = vadPresetVersion,
                    systemLocale = locale
                )
                val local = localProvider.get()
                when {
                    !local.nativeLibReady() -> AttemptSelection.NotReady(
                        type = snapshot.engineType,
                        reason = "sherpa-onnx 原生库未集成，请联系开发者重新构建，或在设置页切换为云端引擎"
                    )

                    !local.modelReady(config) -> AttemptSelection.NotReady(
                        type = snapshot.engineType,
                        reason = "本地模型未就绪，请到设置页下载，或手动切换到云端"
                    )

                    else -> AttemptSelection.Active(
                        engine = local,
                        localConfig = config,
                        cloudRequestConfig = null,
                        provenance = config.toProvenance()
                    )
                }
            }
        }
    }

    sealed class AttemptSelection {
        data class Active(
            val engine: AsrEngine,
            val localConfig: LocalAsrSessionConfig?,
            val cloudRequestConfig: CloudAsrRequestConfig?,
            val provenance: AsrProvenance
        ) : AttemptSelection() {
            init {
                require((localConfig != null) == provenance.engineType.isLocal() &&
                    (cloudRequestConfig != null) == !provenance.engineType.isLocal()) {
                    "Frozen ASR attempt carries the wrong typed request config"
                }
                require(localConfig?.configFingerprint == null ||
                    localConfig.configFingerprint == provenance.configFingerprint) {
                    "ASR attempt config and provenance fingerprints differ"
                }
                require(cloudRequestConfig?.sessionConfig?.configFingerprint == null ||
                    cloudRequestConfig.sessionConfig.configFingerprint == provenance.configFingerprint) {
                    "Cloud ASR request and provenance fingerprints differ"
                }
            }
        }

        data class NotReady(val type: AsrEngineType, val reason: String) : AttemptSelection()
    }
}

private fun app.murmurnote.android.data.preference.AsrRuntimePreferenceSnapshot
    .toAttemptPreferenceSnapshot() = AsrAttemptPreferenceSnapshot(
        engineType = engineType,
        requestedModelId = localModelId,
        languageMode = languageMode,
        manualLanguage = manualLanguage,
        senseVoiceUseItn = senseVoiceUseItn,
        localConcurrency = localConcurrency
    )
