package app.murmurnote.android.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractionJsonParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    @Test
    fun parsesStrictJsonInsideFenceAndThinkBlock() {
        val result = ExtractionJsonParser.parse(
            """
            <think>hidden reasoning</think>
            ```json
            {"summary":"• 主题：会议","items":[{"type":"todo","content":"整理纪要","deadline":null,"sourceTimestampMs":1200}]}
            ```
            """.trimIndent(),
            json
        )

        assertEquals("• 主题：会议", result.summary)
        assertEquals(1, result.items.size)
        assertEquals("todo", result.items[0].type)
        assertEquals(1200L, result.items[0].sourceTimestampMs)
    }

    @Test
    fun repairsObjectLiteralStyleExtractionJson() {
        val result = ExtractionJsonParser.parse(
            """{summary:会议纪要, 重点包括排期,items:[{type:todo,content:整理纪要,deadline:2026-05-11,sourceTimestampMs:null}]}""",
            json
        )

        assertEquals("会议纪要, 重点包括排期", result.summary)
        assertEquals("todo", result.items.single().type)
        assertEquals("整理纪要", result.items.single().content)
        assertEquals("2026-05-11", result.items.single().deadline)
        assertNull(result.items.single().sourceTimestampMs)
    }

    @Test
    fun extractsFirstBalancedJsonObject() {
        val extracted = ExtractionJsonParser.extractJsonObject(
            """prefix {"summary":"a { kept }","items":[]} suffix {"summary":"b","items":[]}"""
        )

        assertEquals("""{"summary":"a { kept }","items":[]}""", extracted)
    }
}
