package app.murmurnote.android.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalCorrectionJsonParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = false
    }

    @Test
    fun parsesLearningDecisionWithoutRepairingArbitraryText() {
        val parsed = PersonalCorrectionJsonParser.parseLearningDecision(
            rawContent = """
                <think>private reasoning</think>
                {"observationId":"event-1","verdict":"ACTIVATE","confidence":"HIGH","reasonCode":"PHONETIC_ASR_ERROR"}
            """.trimIndent(),
            json = json,
        )

        assertEquals("event-1", parsed.observationId)
        assertEquals("ACTIVATE", parsed.verdict)
        assertEquals("HIGH", parsed.confidence)
    }

    @Test
    fun parsesBoundedCandidateDecisions() {
        val parsed = PersonalCorrectionJsonParser.parseCandidateDecisions(
            rawContent = """
                {"schemaVersion":1,"decisions":[
                  {"candidateId":"c1","action":"APPLY","confidence":"HIGH","reasonCode":"USER_TERM_FITS_CONTEXT"},
                  {"candidateId":"c2","action":"KEEP","confidence":"LOW","reasonCode":"AMBIGUOUS_CONTEXT"}
                ]}
            """.trimIndent(),
            json = json,
        )

        assertEquals(2, parsed.size)
        assertEquals("c1", parsed.first().candidateId)
    }

    @Test
    fun rejectsWrongSchemaAndOversizedDecisionArrays() {
        assertThrows(IllegalStateException::class.java) {
            PersonalCorrectionJsonParser.parseCandidateDecisions(
                """{"schemaVersion":2,"decisions":[]}""",
                json,
            )
        }
        val decisions = (0..24).joinToString(",") { index ->
            """{"candidateId":"c$index","action":"KEEP","confidence":"LOW","reasonCode":"AMBIGUOUS_CONTEXT"}"""
        }
        assertThrows(IllegalStateException::class.java) {
            PersonalCorrectionJsonParser.parseCandidateDecisions(
                """{"schemaVersion":1,"decisions":[$decisions]}""",
                json,
            )
        }
    }

    @Test
    fun rejectsUnknownFieldsLenientSyntaxAndTextOutsideTheJsonObject() {
        assertThrows(Exception::class.java) {
            PersonalCorrectionJsonParser.parseLearningDecision(
                rawContent = """
                    {"observationId":"event-1","verdict":"ACTIVATE","confidence":"HIGH",
                     "reasonCode":"PHONETIC_ASR_ERROR","replacementText":"越权新词"}
                """.trimIndent(),
                json = json,
            )
        }
        assertThrows(Exception::class.java) {
            PersonalCorrectionJsonParser.parseCandidateDecisions(
                rawContent = """{schemaVersion:1,decisions:[]}""",
                json = json,
            )
        }
        assertThrows(Exception::class.java) {
            PersonalCorrectionJsonParser.parseCandidateDecisions(
                rawContent = "模型解释：{\"schemaVersion\":1,\"decisions\":[]}",
                json = json,
            )
        }
    }
}
