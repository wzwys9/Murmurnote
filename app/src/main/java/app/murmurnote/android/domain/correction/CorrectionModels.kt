package app.murmurnote.android.domain.correction

enum class CorrectionMatchMode {
    EXACT_TEXT,
    CONTEXTUAL_LLM,
}

enum class CorrectionRuleOrigin {
    USER_DEFINED,
    PERSONAL_LEARNING,
}

object ContextualCorrectionLimits {
    const val MAX_ACTIVE_RULES_PER_ORIGIN: Int = 100
}

class ContextualCorrectionCapacityExceededException(
    val origin: CorrectionRuleOrigin,
    val maximum: Int,
) : IllegalStateException(
    "Contextual correction capacity exceeded for ${origin.name}: maximum=$maximum"
) {
    init {
        require(maximum > 0) { "Contextual correction capacity must be positive" }
    }
}

enum class CorrectionScope {
    RECORDING,
    GLOBAL
}

data class CorrectionRule(
    val id: String,
    val observedText: String,
    val replacementText: String,
    val matchMode: CorrectionMatchMode = CorrectionMatchMode.EXACT_TEXT,
    val origin: CorrectionRuleOrigin = CorrectionRuleOrigin.USER_DEFINED,
    val scope: CorrectionScope,
    val scopeId: String? = null,
    val isEnabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "Rule id must not be blank" }
        require(observedText.isNotEmpty()) { "Observed text must not be empty" }
        require(replacementText.isNotEmpty()) { "Replacement text must not be empty" }
        if (origin == CorrectionRuleOrigin.PERSONAL_LEARNING) {
            require(matchMode == CorrectionMatchMode.CONTEXTUAL_LLM) {
                "Personal learning rules must use contextual matching"
            }
        }
        when (scope) {
            CorrectionScope.RECORDING -> require(!scopeId.isNullOrBlank()) {
                "Recording rules require a scope id"
            }
            CorrectionScope.GLOBAL -> require(scopeId == null) {
                "Global rules must not have a scope id"
            }
        }
    }
}

enum class CorrectionDecision {
    APPLIED,
    REJECTED
}

enum class CorrectionDecisionReason {
    EXACT_TEXT_RULE_APPLIED,
    CONFLICTING_RULES,
    OVERLAPS_HIGHER_PRIORITY
}

data class CorrectionRecord(
    val sourceRuleId: String?,
    val rawStartCodePoint: Int,
    val rawEndCodePointExclusive: Int,
    val originalText: String,
    val replacementText: String,
    val scope: CorrectionScope,
    val decision: CorrectionDecision,
    val decisionReason: CorrectionDecisionReason
)

data class CorrectionResult(
    val rawText: String,
    val correctedText: String,
    val records: List<CorrectionRecord>
)

internal object CodePointText {
    fun values(text: String): IntArray {
        val result = IntArray(text.codePointCount(0, text.length))
        var charIndex = 0
        for (codePointIndex in result.indices) {
            val codePoint = Character.codePointAt(text, charIndex)
            result[codePointIndex] = codePoint
            charIndex += Character.charCount(codePoint)
        }
        return result
    }

    fun slice(text: String, startCodePoint: Int, endCodePointExclusive: Int): String {
        require(startCodePoint in 0..endCodePointExclusive)
        require(endCodePointExclusive <= text.codePointCount(0, text.length))
        val startChar = text.offsetByCodePoints(0, startCodePoint)
        val endChar = text.offsetByCodePoints(startChar, endCodePointExclusive - startCodePoint)
        return text.substring(startChar, endChar)
    }
}
