package app.murmurnote.android.data.remote.glm.dto

import kotlinx.serialization.Serializable

@Serializable
data class AsrChunk(
    val id: String? = null,
    val choices: List<AsrChoice> = emptyList(),
    val created: Long = 0,
    val model: String? = null,
    val `object`: String? = null
)

@Serializable
data class AsrChoice(
    val delta: AsrDelta? = null,
    val message: AsrDelta? = null,
    val finish_reason: String? = null,
    val index: Int = 0
)

@Serializable
data class AsrDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class AsrSyncResponse(
    val id: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val text: String? = null
)
