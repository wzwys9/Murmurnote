package app.murmurnote.android.data.remote.ollama.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ThinkingConfig(
    val type: String = "enabled",            // "enabled" or "disabled"
    val reasoning_effort: String? = null      // "high" or "max", only meaningful when type="enabled"
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: ThinkingConfig? = null,
    val temperature: Double = 0.3,
    val stream: Boolean = false
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatResponseMessage? = null,
    val finish_reason: String? = null
)

@Serializable
data class ChatResponseMessage(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null,
    val reasoning: String? = null  // Ollama 实际返回的字段名
)

@Serializable
data class ChatUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ModelsResponse(
    val data: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    val owned_by: String = ""
)

// === 业务层 DTO（Ollama 返回的 content 解析后映射到这个）===
@Serializable
data class ExtractionResult(
    val summary: String = "",
    val items: List<ExtractedItemDto> = emptyList()
)

@Serializable
data class ExtractedItemDto(
    val type: String,
    val content: String,
    val deadline: String? = null,
    val sourceTimestampMs: Long? = null
)
