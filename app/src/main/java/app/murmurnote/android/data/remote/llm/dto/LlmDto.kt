package app.murmurnote.android.data.remote.llm.dto

import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val message: ChatResponseMessage? = null
)

@Serializable
data class ChatResponseMessage(
    val content: String? = null
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
