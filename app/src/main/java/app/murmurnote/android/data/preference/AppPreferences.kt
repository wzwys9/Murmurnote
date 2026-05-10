package app.murmurnote.android.data.preference

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.murmurnote.android.BuildConfig
import app.murmurnote.android.data.remote.llm.LlmProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "murmurnote_prefs")

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val GLM_API_KEY = stringPreferencesKey("glm_api_key")
        // Legacy key names are kept so existing installs retain their saved LLM settings.
        val OLLAMA_API_KEY = stringPreferencesKey("ollama_api_key")
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
        val REALTIME_PERFORMANCE_MODE = stringPreferencesKey("realtime_performance_mode")
        val LOW_BATTERY_PROTECTION = booleanPreferencesKey("low_battery_protection")
    }

    // 关键：用 contains 判断"用户是否显式设置过"，而不是用 isNotBlank。
    // 否则用户删空保存的空串会被当作"未设置"，立刻被 BuildConfig 兜底值覆盖。
    val glmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        if (prefs.contains(Keys.GLM_API_KEY)) prefs[Keys.GLM_API_KEY].orEmpty()
        else BuildConfig.GLM_API_KEY.orEmpty()
    }

    val llmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        if (prefs.contains(Keys.OLLAMA_API_KEY)) prefs[Keys.OLLAMA_API_KEY].orEmpty()
        else BuildConfig.OLLAMA_API_KEY.orEmpty()
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

    /**
     * ASR 引擎类型字符串（取 AsrEngineType.name）。默认 CLOUD_GLM 保持现有行为，
     * 老用户升级后默认仍走云端。
     */
    val asrEngineType: Flow<String> = context.dataStore.data.map {
        it[Keys.ASR_ENGINE_TYPE] ?: "CLOUD_GLM"
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

    val realtimePerformanceMode: Flow<String> = context.dataStore.data.map {
        it[Keys.REALTIME_PERFORMANCE_MODE] ?: "BALANCED"
    }

    val lowBatteryProtection: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.LOW_BATTERY_PROTECTION] ?: true
    }

    suspend fun setGlmApiKey(key: String) = context.dataStore.edit { it[Keys.GLM_API_KEY] = key.trim() }
    suspend fun setLlmApiKey(key: String) = context.dataStore.edit { it[Keys.OLLAMA_API_KEY] = key.trim() }
    suspend fun setLlmProvider(provider: LlmProvider) = context.dataStore.edit {
        it[Keys.LLM_PROVIDER] = provider.name
        it[Keys.OLLAMA_BASE_URL] = provider.defaultBaseUrl
        it.remove(Keys.OLLAMA_MODEL)
    }
    suspend fun setLlmModel(model: String) = context.dataStore.edit { it[Keys.OLLAMA_MODEL] = model }
    suspend fun setReasoningEffort(effort: String) = context.dataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    suspend fun setGlmBaseUrl(url: String) = context.dataStore.edit { it[Keys.GLM_BASE_URL] = url.trim() }
    suspend fun setLlmBaseUrl(url: String) = context.dataStore.edit { it[Keys.OLLAMA_BASE_URL] = url.trim() }
    suspend fun setOnboardingCompleted(c: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = c }

    suspend fun setSystemPromptOverride(p: String?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.SYSTEM_PROMPT_OVERRIDE) else it[Keys.SYSTEM_PROMPT_OVERRIDE] = p
    }

    suspend fun setUserPromptOverride(p: String?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.USER_PROMPT_OVERRIDE) else it[Keys.USER_PROMPT_OVERRIDE] = p
    }

    suspend fun setDebugForceNetworkFail(v: Boolean) = context.dataStore.edit { it[Keys.DEBUG_FORCE_NETWORK_FAIL] = v }
    suspend fun setDebugSimulateDelayMs(ms: Long) = context.dataStore.edit { it[Keys.DEBUG_SIMULATE_DELAY_MS] = ms.toString() }
    suspend fun setAsrEngineType(t: String) = context.dataStore.edit { it[Keys.ASR_ENGINE_TYPE] = t }
    suspend fun setAsrLocalModelId(id: String) = context.dataStore.edit { it[Keys.ASR_LOCAL_MODEL_ID] = id }
    suspend fun setAsrDownloadMirrorIndex(i: Int) = context.dataStore.edit { it[Keys.ASR_DOWNLOAD_MIRROR_INDEX] = i.toString() }
    suspend fun setAsrBundledInstalled(v: Boolean) = context.dataStore.edit { it[Keys.ASR_BUNDLED_INSTALLED] = v }
    suspend fun setAsrLocalConcurrency(v: Int) = context.dataStore.edit { it[Keys.ASR_LOCAL_CONCURRENCY] = v.coerceIn(1, 3) }
    suspend fun setRealtimePerformanceMode(v: String) = context.dataStore.edit { it[Keys.REALTIME_PERFORMANCE_MODE] = v }
    suspend fun setLowBatteryProtection(v: Boolean) = context.dataStore.edit { it[Keys.LOW_BATTERY_PROTECTION] = v }

    suspend fun hasAllApiKeys(): Boolean = glmApiKey.first().isNotBlank() && llmApiKey.first().isNotBlank()
}
