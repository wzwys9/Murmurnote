package app.murmurnote.android.data.remote.llm

enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val apiKeyHelpUrl: String,
    val requiresApiKey: Boolean = true
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        apiKeyHelpUrl = "https://platform.deepseek.com/api_keys"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiKeyHelpUrl = "https://platform.openai.com/api-keys"
    ),
    ANTHROPIC(
        displayName = "Anthropic",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiKeyHelpUrl = "https://console.anthropic.com/settings/keys"
    ),
    GEMINI(
        displayName = "Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        apiKeyHelpUrl = "https://aistudio.google.com/app/apikey"
    ),
    OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "https://ollama.com/api",
        apiKeyHelpUrl = "https://ollama.com/settings/keys"
    );

    companion object {
        fun parse(value: String?): LlmProvider =
            entries.firstOrNull { it.name == value } ?: DEEPSEEK
    }
}
