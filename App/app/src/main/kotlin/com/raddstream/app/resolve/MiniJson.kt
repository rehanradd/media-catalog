package com.raddstream.app.resolve

/**
 * The smallest JSON reader that covers what this harness parses: the FileBee
 * API replies and the debug sample asset. The app has no JSON library on its
 * classpath, and this harness must not add one for two small reads — so the
 * parser is a plain recursive descent over objects, arrays, strings,
 * numbers, booleans and null. It returns null on malformed input instead of
 * throwing, because a bad reply is a `bad_json` verdict, not a crash.
 */
sealed interface JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    object Null : JsonValue
}

object MiniJson {

    fun parse(text: String): JsonValue? {
        val parser = Parser(text)
        val value = parser.parseValue() ?: return null
        parser.skipWhitespace()
        return if (parser.atEnd()) value else null
    }

    fun JsonValue.obj(key: String): JsonValue? =
        (this as? JsonValue.Obj)?.map?.get(key)

    fun JsonValue.string(key: String): String? =
        (obj(key) as? JsonValue.Str)?.value

    fun JsonValue.long(key: String): Long? =
        (obj(key) as? JsonValue.Num)?.raw?.toLongOrNull()

    fun JsonValue.bool(key: String): Boolean? =
        (obj(key) as? JsonValue.Bool)?.value

    fun JsonValue.array(key: String): List<JsonValue>? =
        (obj(key) as? JsonValue.Arr)?.items

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue? {
            skipWhitespace()
            if (atEnd()) return null
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()?.let { JsonValue.Str(it) }
                't' -> parseLiteral("true")?.let { JsonValue.Bool(true) }
                'f' -> parseLiteral("false")?.let { JsonValue.Bool(false) }
                'n' -> parseLiteral("null")?.let { JsonValue.Null }
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseLiteral(word: String): Unit? {
            if (!text.startsWith(word, pos)) return null
            pos += word.length
            return Unit
        }

        private fun parseNumber(): JsonValue? {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            if (pos == start) return null
            return JsonValue.Num(text.substring(start, pos))
        }

        private fun parseObject(): JsonValue? {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWhitespace()
                val key = parseString() ?: return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') return null
                pos++
                val value = parseValue() ?: return null
                map[key] = value
                skipWhitespace()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(map)
                    }
                    else -> return null
                }
            }
        }

        private fun parseArray(): JsonValue? {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                val value = parseValue() ?: return null
                items.add(value)
                skipWhitespace()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++
            val out = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) return null
                        when (val esc = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) return null
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: return null
                                pos += 4
                                out.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    else -> out.append(c)
                }
            }
            return null
        }
    }
}

/** JSON string escaping for the export writer (the reader above is the parser). */
internal fun jsonEscape(raw: String): String {
    val out = StringBuilder(raw.length + 8)
    for (c in raw) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c.code < 0x20) out.append(String.format("\\u%04x", c.code)) else out.append(c)
        }
    }
    return out.toString()
}
