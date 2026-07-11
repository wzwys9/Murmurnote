package app.murmurnote.android.data.remote.llm

import app.murmurnote.android.domain.correction.PersonalCorrectionPlanValidator
import app.murmurnote.android.domain.correction.UntrustedPersonalCorrectionDecision
import app.murmurnote.android.domain.correction.UntrustedPersonalLearningDecision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object PersonalCorrectionJsonParser {
    fun parseLearningDecision(
        rawContent: String,
        json: Json,
    ): UntrustedPersonalLearningDecision {
        val payload = extractStrictObject(rawContent)
        val decoded = strictJson(json).decodeFromString<LearningDecisionDto>(payload)
        check(decoded.observationId.length in 1..128) { "Invalid observation id length" }
        return UntrustedPersonalLearningDecision(
            observationId = decoded.observationId,
            verdict = decoded.verdict,
            confidence = decoded.confidence,
            reasonCode = decoded.reasonCode,
        )
    }

    fun parseCandidateDecisions(
        rawContent: String,
        json: Json,
    ): List<UntrustedPersonalCorrectionDecision> {
        val payload = extractStrictObject(rawContent)
        val decoded = strictJson(json).decodeFromString<CandidateDecisionEnvelopeDto>(payload)
        check(decoded.schemaVersion == SCHEMA_VERSION) { "Unsupported correction schema" }
        check(decoded.decisions.size <= PersonalCorrectionPlanValidator.MAX_CANDIDATES_PER_RECORDING) {
            "Too many correction decisions"
        }
        return decoded.decisions.map { decision ->
            check(decision.candidateId.length in 1..128) { "Invalid candidate id length" }
            UntrustedPersonalCorrectionDecision(
                candidateId = decision.candidateId,
                action = decision.action,
                confidence = decision.confidence,
                reasonCode = decision.reasonCode,
            )
        }
    }

    private fun extractStrictObject(rawContent: String): String {
        val cleaned = ExtractionJsonParser.stripThink(rawContent).trim()
        check(cleaned.startsWith('{') && cleaned.endsWith('}')) {
            "个性化纠错响应必须只包含 JSON 对象"
        }
        val extracted = ExtractionJsonParser.extractJsonObject(cleaned)
            ?: error("无法从个性化纠错响应抽取 JSON")
        check(extracted == cleaned) { "个性化纠错 JSON 后存在额外内容" }
        return extracted
    }

    private fun strictJson(base: Json): Json = Json(base) {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    private const val SCHEMA_VERSION = 1
}

@Serializable
private data class LearningDecisionDto(
    val observationId: String,
    val verdict: String,
    val confidence: String,
    val reasonCode: String,
)

@Serializable
private data class CandidateDecisionEnvelopeDto(
    val schemaVersion: Int,
    val decisions: List<CandidateDecisionDto>,
)

@Serializable
private data class CandidateDecisionDto(
    val candidateId: String,
    val action: String,
    val confidence: String,
    val reasonCode: String,
)
