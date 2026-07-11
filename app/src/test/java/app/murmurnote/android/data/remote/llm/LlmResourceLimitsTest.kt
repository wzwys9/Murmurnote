package app.murmurnote.android.data.remote.llm

import app.murmurnote.android.util.SizeLimitExceededException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmResourceLimitsTest {

    @Test
    fun longestAcceptedTranscriptNeverCreatesAnOversizedTailChunk() {
        val text = "句。".repeat(LlmResourceLimits.MAX_TRANSCRIPT_CHARS / 2)

        val chunks = LlmResourceLimits.chunkTranscript(text)

        assertTrue(chunks.size <= LlmResourceLimits.MAX_CHUNKS)
        assertTrue(chunks.all { it.length <= LlmResourceLimits.MAX_CHUNK_CHARS })
        assertTrue(chunks.last().length <= LlmResourceLimits.MAX_CHUNK_CHARS)
    }

    @Test
    fun transcriptAndJsonRequestLimitsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmResourceLimits.chunkTranscript(
                "x".repeat(LlmResourceLimits.MAX_TRANSCRIPT_CHARS + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LlmResourceLimits.requireJsonRequestSize(
                "x".repeat(LlmResourceLimits.MAX_JSON_REQUEST_BYTES + 1),
            )
        }
    }

    @Test
    fun shortTranscriptRemainsOneUnchangedChunk() {
        assertEquals(listOf("第一句。第二句。"), LlmResourceLimits.chunkTranscript("第一句。第二句。"))
    }

    @Test
    fun chunkAndOverlapBoundariesNeverSplitAnEmojiSurrogatePair() {
        val text = ("a".repeat(1_399) + "😀").repeat(5)

        val chunks = LlmResourceLimits.chunkTranscript(text)

        assertTrue(chunks.all(::containsOnlyPairedSurrogates))
        assertTrue(chunks.all { it.length <= LlmResourceLimits.MAX_CHUNK_CHARS })
    }

    @Test
    fun jsonResponseReaderRejectsADeclaredOversizedBody() {
        assertEquals("ok", LlmResourceLimits.readJsonResponse("ok".toResponseBody()))
        assertThrows(SizeLimitExceededException::class.java) {
            LlmResourceLimits.readJsonResponse(
                "x".repeat(LlmResourceLimits.MAX_JSON_RESPONSE_BYTES.toInt() + 1)
                    .toResponseBody(),
            )
        }
    }

    private fun containsOnlyPairedSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length ||
                        !Character.isLowSurrogate(value[index + 1])
                    ) {
                        return false
                    }
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }
}
