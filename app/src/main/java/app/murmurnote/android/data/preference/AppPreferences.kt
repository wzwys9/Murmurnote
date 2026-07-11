package app.murmurnote.android.data.preference

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.AsrLanguageMode
import app.murmurnote.android.data.asr.AsrLanguagePolicy
import app.murmurnote.android.data.remote.llm.LlmProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "murmurnote_prefs")

object AsrInstallDefaultPolicy {
    fun resolve(explicitEngine: String?, onboardingCompleted: Boolean): AsrEngineType =
        explicitEngine?.let(AsrEngineType::parse)
            ?: if (onboardingCompleted) AsrEngineType.CLOUD_GLM else AsrEngineType.LOCAL_SENSE_VOICE
}

object AiInstallDefaultPolicy {
    fun resolve(explicitEnabled: Boolean?, onboardingCompleted: Boolean): Boolean =
        explicitEnabled ?: onboardingCompleted
}

class AsrRuntimePreferenceSnapshot(
    val engineType: AsrEngineType,
    val localModelId: String,
    val languageMode: AsrLanguageMode,
    val manualLanguage: String,
    val senseVoiceUseItn: Boolean,
    val localConcurrency: Int,
    val glmBaseUrl: String,
    val glmApiKey: String
) {
    override fun toString(): String =
        "AsrRuntimePreferenceSnapshot(engineType=$engineType, localModelId=$localModelId, " +
            "glmBaseUrl=<redacted>, glmApiKey=<redacted>)"
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val GLM_API_KEY = stringPreferencesKey("glm_api_key")
        // Legacy DataStore key kept so existing installs retain their saved LLM settings.
        val LEGACY_LLM_API_KEY = stringPreferencesKey("ollama_api_key")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val OLLAMA_MODEL = stringPreferencesKey("ollama_model")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val GLM_BASE_URL = stringPreferencesKey("glm_base_url")
        val OLLAMA_BASE_URL = stringPreferencesKey("ollama_base_url")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val SYSTEM_PROMPT_OVERRIDE = stringPreferencesKey("system_prompt_override")
        val USER_PROMPT_OVERRIDE = stringPreferencesKey("user_prompt_override")
        val DEBUG_FORCE_NETWORK_FAIL = booleanPreferencesKey("debug_force_network_fail")
        val DEBUG_SIMULATE_DELAY_MS = stringPreferencesKey("debug_simulate_delay_ms")
        // ASR 引擎切换 / 镜像选择（本地引擎用）
        val ASR_ENGINE_TYPE = stringPreferencesKey("asr_engine_type")
        val ASR_LOCAL_MODEL_ID = stringPreferencesKey("asr_local_model_id")
        val ASR_DOWNLOAD_MIRROR_INDEX = stringPreferencesKey("asr_download_mirror_index")
        // 标记 assets 中预置模型已成功拷贝到 filesDir，避免每次启动都重新校验+拷
        val ASR_BUNDLED_INSTALLED = booleanPreferencesKey("asr_bundled_installed")
        val ASR_LOCAL_CONCURRENCY = intPreferencesKey("asr_local_concurrency")
        val ASR_LANGUAGE_MODE = stringPreferencesKey("asr_language_mode")
        val ASR_MANUAL_LANGUAGE = stringPreferencesKey("asr_manual_language")
        val ASR_SENSE_VOICE_USE_ITN = booleanPreferencesKey("asr_sense_voice_use_itn")
        val REALTIME_PERFORMANCE_MODE = stringPreferencesKey("realtime_performance_mode")
        val LOW_BATTERY_PROTECTION = booleanPreferencesKey("low_battery_protection")
        val AI_EXTRACTION_ENABLED = booleanPreferencesKey("ai_extraction_enabled")
    }

    private fun llmApiKeyFor(provider: LlmProvider) =
        stringPreferencesKey("llm_api_key_${provider.name.lowercase(Locale.US)}")

    val glmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GLM_API_KEY].orEmpty()
    }

    val llmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        val provider = LlmProvider.parse(prefs[Keys.LLM_PROVIDER])
        val providerKey = llmApiKeyFor(provider)
        when {
            prefs.contains(providerKey) -> prefs[providerKey].orEmpty()
            prefs.contains(Keys.LEGACY_LLM_API_KEY) -> prefs[Keys.LEGACY_LLM_API_KEY].orEmpty()
            else -> ""
        }
    }

    val llmProvider: Flow<String> = context.dataStore.data.map {
        it[Keys.LLM_PROVIDER] ?: LlmProvider.DEEPSEEK.name
    }

    val llmModel: Flow<String> = context.dataStore.data.map {
        it[Keys.OLLAMA_MODEL].orEmpty()
    }

    val reasoningEffort: Flow<String> = context.dataStore.data.map {
        it[Keys.REASONING_EFFORT] ?: "high"
    }

    val glmBaseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.GLM_BASE_URL]?.takeIf { it.isNotBlank() } ?: "https://open.bigmodel.cn/api/paas/v4/"
    }

    val llmBaseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.OLLAMA_BASE_URL]?.takeIf { it.isNotBlank() } ?: LlmProvider.DEEPSEEK.defaultBaseUrl
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val systemPromptOverride: Flow<String?> = context.dataStore.data.map {
        it[Keys.SYSTEM_PROMPT_OVERRIDE]
    }

    val userPromptOverride: Flow<String?> = context.dataStore.data.map {
        it[Keys.USER_PROMPT_OVERRIDE]
    }

    val debugForceNetworkFail: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.DEBUG_FORCE_NETWORK_FAIL] ?: false
    }

    val debugSimulateDelayMs: Flow<Long> = context.dataStore.data.map {
        it[Keys.DEBUG_SIMULATE_DELAY_MS]?.toLongOrNull() ?: 0L
    }

    /** 新安装默认本地 SenseVoice；已完成 onboarding 的旧安装保持云端默认。 */
    val asrEngineType: Flow<String> = context.dataStore.data.map { prefs ->
        AsrInstallDefaultPolicy.resolve(
            explicitEngine = prefs[Keys.ASR_ENGINE_TYPE],
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        ).name
    }

    /** 模型下载镜像索引（0=GitHub 直连，1+=AsrModelUrls.MIRROR_PREFIXES）。默认 0。 */
    val asrDownloadMirrorIndex: Flow<Int> = context.dataStore.data.map {
        it[Keys.ASR_DOWNLOAD_MIRROR_INDEX]?.toIntOrNull() ?: 0
    }

    /** 当前选中的本地 ASR 模型。默认 SenseVoiceSmall，旧安装升级后行为不变。 */
    val asrLocalModelId: Flow<String> = context.dataStore.data.map {
        it[Keys.ASR_LOCAL_MODEL_ID] ?: "sense_voice_zh_en_ja_ko_yue"
    }

    /** assets 中的预置模型是否已经拷贝到 filesDir。 */
    val asrBundledInstalled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ASR_BUNDLED_INSTALLED] ?: false
    }

    /** 本地小模型并行识别倍速，1..3。大模型会被运行时强制降到 1。 */
    val asrLocalConcurrency: Flow<Int> = context.dataStore.data.map {
        it[Keys.ASR_LOCAL_CONCURRENCY] ?: 1
    }

    val asrLanguageMode: Flow<AsrLanguageMode> = context.dataStore.data.map {
        AsrLanguageMode.parse(it[Keys.ASR_LANGUAGE_MODE])
    }

    val asrManualLanguage: Flow<String> = context.dataStore.data.map {
        AsrLanguagePolicy.normalizeManualLanguage(it[Keys.ASR_MANUAL_LANGUAGE].orEmpty())
            ?: AsrLanguagePolicy.AUTO
    }

    val asrSenseVoiceUseItn: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ASR_SENSE_VOICE_USE_ITN] ?: true
    }

    val realtimePerformanceMode: Flow<String> = context.dataStore.data.map {
        it[Keys.REALTIME_PERFORMANCE_MODE] ?: "BALANCED"
    }

    val lowBatteryProtection: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.LOW_BATTERY_PROTECTION] ?: true
    }

    val aiExtractionEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        AiInstallDefaultPolicy.resolve(
            explicitEnabled = prefs[Keys.AI_EXTRACTION_ENABLED],
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
        )
    }

    /** Reads every ASR-affecting preference from one DataStore generation. */
    suspend fun snapshotAsrRuntimePreferences(): AsrRuntimePreferenceSnapshot {
        val prefs = context.dataStore.data.first()
        return AsrRuntimePreferenceSnapshot(
            engineType = AsrInstallDefaultPolicy.resolve(
                explicitEngine = prefs[Keys.ASR_ENGINE_TYPE],
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false
            ),
            localModelId = prefs[Keys.ASR_LOCAL_MODEL_ID] ?: "sense_voice_zh_en_ja_ko_yue",
            languageMode = AsrLanguageMode.parse(prefs[Keys.ASR_LANGUAGE_MODE]),
            manualLanguage = AsrLanguagePolicy.normalizeManualLanguage(
                prefs[Keys.ASR_MANUAL_LANGUAGE].orEmpty()
            ) ?: AsrLanguagePolicy.AUTO,
            senseVoiceUseItn = prefs[Keys.ASR_SENSE_VOICE_USE_ITN] ?: true,
            localConcurrency = (prefs[Keys.ASR_LOCAL_CONCURRENCY] ?: 1).coerceIn(1, 3),
            glmBaseUrl = prefs[Keys.GLM_BASE_URL]
                ?.takeIf { it.isNotBlank() }
                ?: "https://open.bigmodel.cn/api/paas/v4/",
            glmApiKey = prefs[Keys.GLM_API_KEY].orEmpty()
        )
    }

    suspend fun setGlmApiKey(key: String) = context.dataStore.edit { it[Keys.GLM_API_KEY] = key.trim() }
    suspend fun setLlmApiKey(key: String) {
        val provider = LlmProvider.parse(context.dataStore.data.first()[Keys.LLM_PROVIDER])
        setLlmApiKey(provider, key)
    }
    suspend fun setLlmApiKey(provider: LlmProvider, key: String) {
        context.dataStore.edit { it[llmApiKeyFor(provider)] = key.trim() }
    }
    suspend fun setLlmProvider(provider: LlmProvider) = context.dataStore.edit {
        if (it.contains(Keys.LEGACY_LLM_API_KEY)) {
            val previousProvider = LlmProvider.parse(it[Keys.LLM_PROVIDER])
            val previousProviderKey = llmApiKeyFor(previousProvider)
            if (!it.contains(previousProviderKey)) {
                it[previousProviderKey] = it[Keys.LEGACY_LLM_API_KEY].orEmpty()
            }
            it.remove(Keys.LEGACY_LLM_API_KEY)
        }
        it[Keys.LLM_PROVIDER] = provider.name
        it[Keys.OLLAMA_BASE_URL] = provider.defaultBaseUrl
        it.remove(Keys.OLLAMA_MODEL)
    }
    suspend fun setLlmModel(model: String) = context.dataStore.edit { it[Keys.OLLAMA_MODEL] = model }
    suspend fun setReasoningEffort(effort: String) = context.dataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    suspend fun setGlmBaseUrl(url: String) = context.dataStore.edit { it[Keys.GLM_BASE_URL] = url.trim() }
    suspend fun setLlmBaseUrl(url: String) = context.dataStore.edit { it[Keys.OLLAMA_BASE_URL] = url.trim() }
    suspend fun completeOnboarding() = context.dataStore.edit { prefs ->
        if (!prefs.contains(Keys.ASR_ENGINE_TYPE)) {
            prefs[Keys.ASR_ENGINE_TYPE] = AsrEngineType.LOCAL_SENSE_VOICE.name
        }
        if (!prefs.contains(Keys.AI_EXTRACTION_ENABLED)) {
            prefs[Keys.AI_EXTRACTION_ENABLED] = false
        }
        prefs[Keys.ONBOARDING_COMPLETED] = true
    }

    suspend fun setOnboardingCompleted(c: Boolean) {
        if (c) completeOnboarding()
        else context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = false }
    }

    suspend fun setSystemPromptOverride(p: String?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.SYSTEM_PROMPT_OVERRIDE) else it[Keys.SYSTEM_PROMPT_OVERRIDE] = p
    }

    suspend fun setUserPromptOverride(p: String?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.USER_PROMPT_OVERRIDE) else it[Keys.USER_PROMPT_OVERRIDE] = p
    }

    suspend fun setDebugForceNetworkFail(v: Boolean) = context.dataStore.edit { it[Keys.DEBUG_FORCE_NETWORK_FAIL] = v }
    suspend fun setDebugSimulateDelayMs(ms: Long) = context.dataStore.edit { it[Keys.DEBUG_SIMULATE_DELAY_MS] = ms.toString() }
    suspend fun setAsrEngineType(t: String) = context.dataStore.edit {
        it[Keys.ASR_ENGINE_TYPE] = AsrEngineType.parse(t).name
    }
    suspend fun setAsrLocalModelId(id: String) = context.dataStore.edit { it[Keys.ASR_LOCAL_MODEL_ID] = id }
    suspend fun setAsrDownloadMirrorIndex(i: Int) = context.dataStore.edit { it[Keys.ASR_DOWNLOAD_MIRROR_INDEX] = i.toString() }
    suspend fun setAsrBundledInstalled(v: Boolean) = context.dataStore.edit { it[Keys.ASR_BUNDLED_INSTALLED] = v }
    suspend fun setAsrLocalConcurrency(v: Int) = context.dataStore.edit { it[Keys.ASR_LOCAL_CONCURRENCY] = v.coerceIn(1, 3) }
    suspend fun setAsrLanguageMode(mode: AsrLanguageMode) = context.dataStore.edit {
        it[Keys.ASR_LANGUAGE_MODE] = mode.name
    }
    suspend fun setAsrManualLanguage(language: String): Boolean {
        val normalized = AsrLanguagePolicy.normalizeManualLanguage(language) ?: return false
        context.dataStore.edit { it[Keys.ASR_MANUAL_LANGUAGE] = normalized }
        return true
    }
    suspend fun setAsrSenseVoiceUseItn(enabled: Boolean) = context.dataStore.edit {
        it[Keys.ASR_SENSE_VOICE_USE_ITN] = enabled
    }
    suspend fun setRealtimePerformanceMode(v: String) = context.dataStore.edit { it[Keys.REALTIME_PERFORMANCE_MODE] = v }
    suspend fun setLowBatteryProtection(v: Boolean) = context.dataStore.edit { it[Keys.LOW_BATTERY_PROTECTION] = v }
    suspend fun setAiExtractionEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AI_EXTRACTION_ENABLED] = v }

    suspend fun hasAllApiKeys(): Boolean = glmApiKey.first().isNotBlank() && llmApiKey.first().isNotBlank()
}
