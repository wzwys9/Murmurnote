package app.murmurnote.android.domain.correction

internal data class SafeLexiconRuleInput(
    val observedText: String,
    val replacementText: String,
)

internal enum class SafeLexiconCreateAction {
    INSERT,
    REUSE_ENABLED,
    REACTIVATE,
}

internal data class SafeLexiconCreateDecision(
    val action: SafeLexiconCreateAction,
    val existingRuleId: String? = null,
)

internal object SafeLexiconRulePolicy {
    const val MIN_CODE_POINTS: Int = 2
    const val MAX_CODE_POINTS: Int = 32

    fun normalize(
        observedText: String,
        replacementText: String,
    ): SafeLexiconRuleInput {
        require(!observedText.containsUnsafeCodePoint()) {
            "识别结果不能包含换行、控制或不可见格式字符"
        }
        require(!replacementText.containsUnsafeCodePoint()) {
            "正确写法不能包含换行、控制或不可见格式字符"
        }

        val normalizedObserved = observedText.trim()
        val normalizedReplacement = replacementText.trim()
        require(normalizedObserved.codePointLength() in MIN_CODE_POINTS..MAX_CODE_POINTS) {
            "识别结果需要 $MIN_CODE_POINTS-$MAX_CODE_POINTS 个字符"
        }
        require(normalizedReplacement.codePointLength() in MIN_CODE_POINTS..MAX_CODE_POINTS) {
            "正确写法需要 $MIN_CODE_POINTS-$MAX_CODE_POINTS 个字符"
        }
        require(normalizedObserved != normalizedReplacement) { "识别结果和正确写法不能相同" }

        return SafeLexiconRuleInput(
            observedText = normalizedObserved,
            replacementText = normalizedReplacement,
        )
    }

    fun decideCreate(
        input: SafeLexiconRuleInput,
        existingGlobalRules: List<CorrectionRule>,
    ): SafeLexiconCreateDecision {
        val globalRules = existingGlobalRules.filter { it.scope == CorrectionScope.GLOBAL }
        val exact = globalRules.firstOrNull { rule ->
            rule.observedText == input.observedText &&
                rule.replacementText == input.replacementText
        }
        if (exact != null) {
            val conflictsWithEnabledRule = globalRules.any { rule ->
                rule.id != exact.id &&
                    rule.isEnabled &&
                    rule.conflictsWith(input)
            }
            require(!conflictsWithEnabledRule) {
                "这个识别结果已有其他写法，请先处理原词条"
            }
            return SafeLexiconCreateDecision(
                action = if (exact.isEnabled) {
                    SafeLexiconCreateAction.REUSE_ENABLED
                } else {
                    SafeLexiconCreateAction.REACTIVATE
                },
                existingRuleId = exact.id,
            )
        }

        val conflicts = globalRules.any { rule -> rule.conflictsWith(input) }
        require(!conflicts) { "这个识别结果已有其他写法，请先处理原词条" }
        return SafeLexiconCreateDecision(SafeLexiconCreateAction.INSERT)
    }

    private fun CorrectionRule.conflictsWith(input: SafeLexiconRuleInput): Boolean =
        observedText == input.observedText ||
            (observedText == input.replacementText && replacementText == input.observedText)

    private fun String.containsUnsafeCodePoint(): Boolean {
        var charOffset = 0
        while (charOffset < length) {
            val codePoint = Character.codePointAt(this, charOffset)
            val type = Character.getType(codePoint)
            if (Character.isISOControl(codePoint) ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                (type == Character.SPACE_SEPARATOR.toInt() && codePoint != ' '.code) ||
                type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() ||
                type == Character.UNASSIGNED.toInt()
            ) {
                return true
            }
            charOffset += Character.charCount(codePoint)
        }
        return false
    }

    private fun String.codePointLength(): Int = codePointCount(0, length)
}
