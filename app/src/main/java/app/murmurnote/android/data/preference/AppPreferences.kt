package app.murmurnote.android.data.preference

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.murmurnote.android.BuildConfig
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
        val OLLAMA_API_KEY = stringPreferencesKey("ollama_api_key")
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
        val ASR_DOWNLOAD_MIRROR_INDEX = stringPreferencesKey("asr_download_mirror_index")
        // 标记 assets 中预置模型已成功拷贝到 filesDir，避免每次启动都重新校验+拷
        val ASR_BUNDLED_INSTALLED = booleanPreferencesKey("asr_bundled_installed")
        val ASR_LOCAL_CONCURRENCY = intPreferencesKey("asr_local_concurrency")
    }

    // 关键：用 contains 判断"用户是否显式设置过"，而不是用 isNotBlank。
    // 否则用户删空保存的空串会被当作"未设置"，立刻被 BuildConfig 兜底值覆盖。
    val glmApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        if (prefs.contains(Keys.GLM_API_KEY)) prefs[Keys.GLM_API_KEY].orEmpty()
        else BuildConfig.GLM_API_KEY.orEmpty()
    }

    val ollamaApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        if (prefs.contains(Keys.OLLAMA_API_KEY)) prefs[Keys.OLLAMA_API_KEY].orEmpty()
        else BuildConfig.OLLAMA_API_KEY.orEmpty()
    }

    val ollamaModel: Flow<String> = context.dataStore.data.map {
        it[Keys.OLLAMA_MODEL] ?: "deepseek-v4-flash"
    }

    val reasoningEffort: Flow<String> = context.dataStore.data.map {
        it[Keys.REASONING_EFFORT] ?: "low"
    }

    val glmBaseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.GLM_BASE_URL]?.takeIf { it.isNotBlank() } ?: "https://open.bigmodel.cn/api/paas/v4/"
    }

    val ollamaBaseUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.OLLAMA_BASE_URL]?.takeIf { it.isNotBlank() } ?: "https://ollama.com/v1/"
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

    /** assets 中的预置模型是否已经拷贝到 filesDir。 */
    val asrBundledInstalled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ASR_BUNDLED_INSTALLED] ?: false
    }

    /** 本地 ASR 并发度（1–3）。每个并发槽位创建一个独立的 OfflineRecognizer，约 200MB/个。 */
    val asrLocalConcurrency: Flow<Int> = context.dataStore.data.map {
        it[Keys.ASR_LOCAL_CONCURRENCY] ?: 1
    }

    suspend fun setGlmApiKey(key: String) = context.dataStore.edit { it[Keys.GLM_API_KEY] = key.trim() }
    suspend fun setOllamaApiKey(key: String) = context.dataStore.edit { it[Keys.OLLAMA_API_KEY] = key.trim() }
    suspend fun setOllamaModel(model: String) = context.dataStore.edit { it[Keys.OLLAMA_MODEL] = model }
    suspend fun setReasoningEffort(effort: String) = context.dataStore.edit { it[Keys.REASONING_EFFORT] = effort }
    suspend fun setGlmBaseUrl(url: String) = context.dataStore.edit { it[Keys.GLM_BASE_URL] = url.trim() }
    suspend fun setOllamaBaseUrl(url: String) = context.dataStore.edit { it[Keys.OLLAMA_BASE_URL] = url.trim() }
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
    suspend fun setAsrDownloadMirrorIndex(i: Int) = context.dataStore.edit { it[Keys.ASR_DOWNLOAD_MIRROR_INDEX] = i.toString() }
    suspend fun setAsrBundledInstalled(v: Boolean) = context.dataStore.edit { it[Keys.ASR_BUNDLED_INSTALLED] = v }
    suspend fun setAsrLocalConcurrency(v: Int) = context.dataStore.edit { it[Keys.ASR_LOCAL_CONCURRENCY] = v.coerceIn(1, 3) }

    suspend fun hasAllApiKeys(): Boolean = glmApiKey.first().isNotBlank() && ollamaApiKey.first().isNotBlank()
}
