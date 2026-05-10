package app.murmurnote.android.data.remote.llm.dto

import kotlinx.serialization.Serializable

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
    val reasoning_content: String? = null
)

@Serializable
data class ChatUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

// === 业务层 DTO（LLM 返回的 content 解析后映射到这个）===
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
