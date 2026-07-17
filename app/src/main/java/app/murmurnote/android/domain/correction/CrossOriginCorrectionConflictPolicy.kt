package app.murmurnote.android.domain.correction

object CrossOriginCorrectionConflictPolicy {
    fun conflicts(
        userDefined: CorrectionRule,
        learned: CorrectionRule,
    ): Boolean {
        require(userDefined.origin == CorrectionRuleOrigin.USER_DEFINED) {
            "Expected a user-defined correction rule"
        }
        require(learned.origin == CorrectionRuleOrigin.PERSONAL_LEARNING) {
            "Expected a personal-learning correction rule"
        }
        return userDefined.observedText == learned.observedText ||
            userDefined.replacementText == learned.observedText ||
            userDefined.observedText == learned.replacementText
    }
}
