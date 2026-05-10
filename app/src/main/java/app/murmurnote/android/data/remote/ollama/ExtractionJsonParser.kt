package app.murmurnote.android.data.remote.ollama

import app.murmurnote.android.data.remote.ollama.dto.ExtractionResult
import kotlinx.serialization.json.Json

internal object ExtractionJsonParser {

    fun parse(rawContent: String, json: Json): ExtractionResult {
        val cleaned = stripThink(rawContent).trim()
        val jsonObject = extractJsonObject(cleaned)
            ?: error("无法从响应抽取 JSON: ${cleaned.take(400)}")

        return runCatching {
            json.decodeFromString(ExtractionResult.serializer(), jsonObject)
        }.getOrElse {
            json.decodeFromString(ExtractionResult.serializer(), repairObjectLiteralJson(jsonObject))
        }
    }

    fun stripThink(value: String): String =
        Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE).replace(value, "")
            .replace("```json", "")
            .replace("```", "")

    fun extractJsonObject(value: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in value.indices) {
            val c = value[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) return value.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun repairObjectLiteralJson(raw: String): String =
        quoteBareValues(quoteBareSummary(quoteBareKeys(raw)))

    private fun quoteBareKeys(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        var i = 0
        var inString = false
        var escape = false
        while (i < raw.length) {
            val c = raw[i]
            if (escape) {
                out.append(c)
                escape = false
                i++
                continue
            }
            if (c == '\\' && inString) {
                out.append(c)
                escape = true
                i++
                continue
            }
            if (c == '"') {
                out.append(c)
                inString = !inString
                i++
                continue
            }
            if (!inString && (c == '{' || c == ',')) {
                out.append(c)
                i++
                while (i < raw.length && raw[i].isWhitespace()) {
                    out.append(raw[i])
                    i++
                }
                val keyStart = i
                if (i < raw.length && (raw[i].isLetter() || raw[i] == '_')) {
                    i++
                    while (i < raw.length && (raw[i].isLetterOrDigit() || raw[i] == '_')) i++
                    val keyEnd = i
                    var j = i
                    while (j < raw.length && raw[j].isWhitespace()) j++
                    if (j < raw.length && raw[j] == ':') {
                        out.append('"').append(raw, keyStart, keyEnd).append('"')
                        continue
                    }
                }
                if (i > keyStart) {
                    out.append(raw, keyStart, i)
                } else if (i < raw.length && raw[i] == '"') {
                    i = copyQuotedString(raw, i, out)
                } else if (i < raw.length) {
                    out.append(raw[i])
                    i++
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun quoteBareSummary(raw: String): String {
        val summaryRegex = Regex(
            """("summary"\s*:\s*)(?!["{\[]|null\b|true\b|false\b)([\s\S]*?)(,\s*"items"\s*:)"""
        )
        return summaryRegex.replace(raw) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2].trim()
            val suffix = match.groupValues[3]
            """$prefix"${escapeJsonString(value)}"$suffix"""
        }
    }

    private fun copyQuotedString(raw: String, start: Int, out: StringBuilder): Int {
        out.append(raw[start])
        var i = start + 1
        var escape = false
        while (i < raw.length) {
            val c = raw[i]
            out.append(c)
            i++
            if (escape) {
                escape = false
            } else if (c == '\\') {
                escape = true
            } else if (c == '"') {
                break
            }
        }
        return i
    }

    private fun quoteBareValues(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        var i = 0
        var inString = false
        var escape = false
        while (i < raw.length) {
            val c = raw[i]
            if (escape) {
                out.append(c)
                escape = false
                i++
                continue
            }
            if (c == '\\' && inString) {
                out.append(c)
                escape = true
                i++
                continue
            }
            if (c == '"') {
                out.append(c)
                inString = !inString
                i++
                continue
            }
            if (!inString && c == ':') {
                out.append(c)
                i++
                while (i < raw.length && raw[i].isWhitespace()) {
                    out.append(raw[i])
                    i++
                }
                if (i >= raw.length) break
                val next = raw[i]
                if (next == '"' || next == '{' || next == '[') {
                    continue
                }
                val valueStart = i
                while (i < raw.length && raw[i] !in charArrayOf(',', '}', ']')) i++
                val token = raw.substring(valueStart, i).trim()
                if (isJsonLiteral(token)) {
                    out.append(token)
                } else {
                    out.append('"').append(escapeJsonString(token)).append('"')
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isJsonLiteral(token: String): Boolean =
        token == "null" ||
            token == "true" ||
            token == "false" ||
            Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""").matches(token)

    private fun escapeJsonString(value: String): String =
        buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
}
