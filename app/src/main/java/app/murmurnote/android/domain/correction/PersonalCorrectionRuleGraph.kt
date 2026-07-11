package app.murmurnote.android.domain.correction

object PersonalCorrectionRuleGraph {
    fun wouldCreateCycle(
        observedText: String,
        replacementText: String,
        activeRules: List<CorrectionRule>,
    ): Boolean {
        if (observedText == replacementText) return true
        val outgoing = activeRules
            .asSequence()
            .filter {
                it.isEnabled &&
                    it.scope == CorrectionScope.GLOBAL &&
                    it.matchMode == CorrectionMatchMode.CONTEXTUAL_LLM
            }
            .groupBy(CorrectionRule::observedText, CorrectionRule::replacementText)
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.addLast(replacementText)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current == observedText) return true
            if (!visited.add(current)) continue
            outgoing[current].orEmpty().forEach(pending::addLast)
        }
        return false
    }
}
