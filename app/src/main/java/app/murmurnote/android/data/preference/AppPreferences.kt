package app.murmurnote.android.data.preference

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    suspend fun hasAllApiKeys(): Boolean = glmApiKey.first().isNotBlank() && ollamaApiKey.first().isNotBlank()
}
