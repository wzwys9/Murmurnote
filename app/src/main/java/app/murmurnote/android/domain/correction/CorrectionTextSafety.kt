package app.murmurnote.android.domain.correction

internal fun String.containsUnsafeCorrectionCodePoint(): Boolean {
    var charOffset = 0
    while (charOffset < length) {
        val codePoint = Character.codePointAt(this, charOffset)
        val type = Character.getType(codePoint)
        if (
            Character.isISOControl(codePoint) ||
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

internal fun String.correctionCodePointLength(): Int = codePointCount(0, length)
