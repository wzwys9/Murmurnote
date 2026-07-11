package app.murmurnote.android.domain.correction

enum class PinyinRelation {
    EXACT_PINYIN,
    NEAR_PINYIN,
    NOT_PHONETIC,
    UNAVAILABLE,
}

object PinyinRelationClassifier {
    private const val MAX_NEAR_SYLLABLE_DISTANCE = 2

    fun classify(
        observedSyllables: List<String>?,
        replacementSyllables: List<String>?,
    ): PinyinRelation {
        if (observedSyllables.isNullOrEmpty() || replacementSyllables.isNullOrEmpty()) {
            return PinyinRelation.UNAVAILABLE
        }
        if (observedSyllables.size != replacementSyllables.size) {
            return PinyinRelation.NOT_PHONETIC
        }

        val observed = observedSyllables.map(::normalize)
        val replacement = replacementSyllables.map(::normalize)
        if (observed.any(String::isEmpty) || replacement.any(String::isEmpty)) {
            return PinyinRelation.UNAVAILABLE
        }
        if (observed == replacement) return PinyinRelation.EXACT_PINYIN

        val differences = observed.indices.filter { observed[it] != replacement[it] }
        return if (
            differences.size == 1 &&
            editDistance(
                observed[differences.single()],
                replacement[differences.single()],
            ) <= MAX_NEAR_SYLLABLE_DISTANCE
        ) {
            PinyinRelation.NEAR_PINYIN
        } else {
            PinyinRelation.NOT_PHONETIC
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .filter { it in 'a'..'z' || it == 'ü' }

    private fun editDistance(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { firstIndex, firstChar ->
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondChar ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (firstChar == secondChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous[second.length]
    }
}
