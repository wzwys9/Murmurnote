package app.murmurnote.android.domain.correction

class ContextualCorrectionRuntimePolicy private constructor(
    val includeUserDefinedRules: Boolean,
    val includePersonalLearningRules: Boolean,
    val canReviewLearning: Boolean,
    val canReviewCandidates: Boolean,
) {
    fun includes(origin: CorrectionRuleOrigin): Boolean = when (origin) {
        CorrectionRuleOrigin.USER_DEFINED -> includeUserDefinedRules
        CorrectionRuleOrigin.PERSONAL_LEARNING -> includePersonalLearningRules
    }

    companion object {
        fun resolve(
            customDictionaryEnabled: Boolean,
            personalLearningEnabled: Boolean,
            llmConfigured: Boolean,
        ): ContextualCorrectionRuntimePolicy {
            val includeUserDefined = customDictionaryEnabled && llmConfigured
            val includePersonalLearning = personalLearningEnabled && llmConfigured
            return ContextualCorrectionRuntimePolicy(
                includeUserDefinedRules = includeUserDefined,
                includePersonalLearningRules = includePersonalLearning,
                canReviewLearning = includePersonalLearning,
                canReviewCandidates = includeUserDefined || includePersonalLearning,
            )
        }
    }
}
