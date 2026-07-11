package app.murmurnote.android.domain.correction

object PersonalCorrectionTextApplier {
    fun apply(
        text: String,
        candidates: List<PersonalCorrectionCandidate>,
    ): String {
        if (candidates.isEmpty()) return text
        require(candidates.map { it.segmentId }.distinct().size == 1) {
            "Candidates for one text must belong to one segment"
        }
        val ordered = candidates.sortedBy { it.startCodePoint }
        ordered.forEachIndexed { index, candidate ->
            require(
                CodePointText.slice(
                    text,
                    candidate.startCodePoint,
                    candidate.endCodePointExclusive,
                ) == candidate.observedText,
            ) { "Personal correction candidate no longer matches text" }
            if (index > 0) {
                require(ordered[index - 1].endCodePointExclusive <= candidate.startCodePoint) {
                    "Personal correction candidates overlap"
                }
            }
        }

        return buildString {
            var cursor = 0
            ordered.forEach { candidate ->
                append(CodePointText.slice(text, cursor, candidate.startCodePoint))
                append(candidate.replacementText)
                cursor = candidate.endCodePointExclusive
            }
            append(CodePointText.slice(text, cursor, text.codePointCount(0, text.length)))
        }
    }
}
