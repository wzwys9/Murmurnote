package app.murmurnote.android.data.remote.llm

import app.murmurnote.android.util.BoundedStreams
import app.murmurnote.android.util.SizeLimitExceededException
import kotlin.math.ceil
import okhttp3.ResponseBody

internal object LlmResourceLimits {
    const val MAX_CHUNKS = 6
    const val MAX_CHUNK_CHARS = 9_000
    const val MAX_TRANSCRIPT_CHARS = 50_000
    const val MAX_JSON_REQUEST_BYTES = 1024 * 1024
    const val MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024L
    const val JSON_CALL_TIMEOUT_MS = 2 * 60 * 1000L

    private const val BASE_CHUNK_CHARS = 1_400
    private const val OVERLAP_CHARS = 200

    fun chunkTranscript(text: String): List<String> {
        requireTranscriptSize(text)
        if (text.length <= BASE_CHUNK_CHARS) return listOf(text)

        val chunkChars = maxOf(
            BASE_CHUNK_CHARS,
            ceil(text.length / MAX_CHUNKS.toDouble()).toInt(),
        )
        val baseChunks = text.chunkWithoutSplittingSurrogatePairs(chunkChars)
        check(baseChunks.size <= MAX_CHUNKS)
        return baseChunks.mapIndexed { index, chunk ->
            if (index == 0) {
                chunk
            } else {
                baseChunks[index - 1].unicodeSafeSuffix(OVERLAP_CHARS) + chunk
            }
        }.also { chunks ->
            check(chunks.all { it.length <= MAX_CHUNK_CHARS })
        }
    }

    fun requireTranscriptSize(text: String) {
        require(text.length <= MAX_TRANSCRIPT_CHARS) {
            "转录文本过长，暂不调用 AI 整理，请缩短录音或分段处理"
        }
    }

    fun requireJsonRequestSize(body: String) {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_JSON_REQUEST_BYTES) {
            "AI 请求内容超过安全上限"
        }
    }

    fun readJsonResponse(responseBody: ResponseBody?): String {
        if (responseBody == null) return ""
        if (responseBody.contentLength() > MAX_JSON_RESPONSE_BYTES) {
            throw SizeLimitExceededException(MAX_JSON_RESPONSE_BYTES)
        }
        return responseBody.byteStream().use { input ->
            BoundedStreams.readUtf8(input, MAX_JSON_RESPONSE_BYTES)
        }
    }

    private fun String.chunkWithoutSplittingSurrogatePairs(targetChars: Int): List<String> {
        val chunks = ArrayList<String>()
        var start = 0
        while (start < length) {
            var end = (start + targetChars).coerceAtMost(length)
            if (end < length &&
                Character.isHighSurrogate(this[end - 1]) &&
                Character.isLowSurrogate(this[end])
            ) {
                end += 1
            }
            chunks += substring(start, end)
            start = end
        }
        return chunks
    }

    private fun String.unicodeSafeSuffix(maxChars: Int): String {
        var start = (length - maxChars).coerceAtLeast(0)
        if (start > 0 &&
            start < length &&
            Character.isHighSurrogate(this[start - 1]) &&
            Character.isLowSurrogate(this[start])
        ) {
            start -= 1
        }
        return substring(start)
    }
}
