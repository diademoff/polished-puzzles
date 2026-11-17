package name.boyle.chris.sgtpuzzles.parity

/**
 * A small JSON reader and writer for the oracle protocol.
 *
 * The protocol is flat: objects, arrays, strings, integers, and
 * booleans. The project does not depend on a JSON library, so this file
 * covers exactly that subset and rejects everything else.
 */
sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Long) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()

    /** JSON `null`, used by the `choiceKws` field of a settings item. */
    data object Null : JsonValue()
}

object Json {

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipSpace()
        require(reader.atEnd()) { "trailing data at offset ${reader.position}" }
        return value
    }

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipSpace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            skipSpace()
            require(position < text.length) { "unexpected end of input" }
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) readNumber()
                else throw IllegalArgumentException("unexpected '$c' at offset $position")
            }
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue {
            require(text.startsWith(word, position)) { "expected $word at offset $position" }
            position += word.length
            return value
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipSpace()
                val key = readString()
                skipSpace()
                expect(':')
                fields[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at offset $position")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(readValue())
                skipSpace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.Arr(items)
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at offset $position")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(position < text.length) { "unterminated string" }
                when (val c = text[position++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(position < text.length) { "unterminated escape" }
                        when (val e = text[position++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(position + 4 <= text.length) { "short unicode escape" }
                                val hex = text.substring(position, position + 4)
                                out.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e' at offset $position")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            while (position < text.length && text[position].isDigit()) position++
            require(position > start) { "bad number at offset $start" }
            return JsonValue.Num(text.substring(start, position).toLong())
        }

        private fun peek(): Char {
            require(position < text.length) { "unexpected end of input" }
            return text[position]
        }

        private fun expect(c: Char) {
            require(peek() == c) { "expected '$c' at offset $position" }
            position++
        }
    }
}

/** Field access with a clear message when the oracle breaks the contract. */
fun JsonValue.Obj.field(key: String): JsonValue =
    fields[key] ?: throw IllegalStateException("response has no '$key' field: $fields")

fun JsonValue.Obj.string(key: String): String =
    (field(key) as? JsonValue.Str)?.value
        ?: throw IllegalStateException("field '$key' is not a string: $fields")

/** A string field that may be absent or JSON null. */
fun JsonValue.Obj.nullableString(key: String): String? =
    when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw IllegalStateException("field '$key' is not a string or null: $fields")
    }

fun JsonValue.Obj.bool(key: String): Boolean =
    (field(key) as? JsonValue.Bool)?.value
        ?: throw IllegalStateException("field '$key' is not a boolean: $fields")

fun JsonValue.Obj.number(key: String): Long =
    (field(key) as? JsonValue.Num)?.value
        ?: throw IllegalStateException("field '$key' is not a number: $fields")

fun JsonValue.Obj.numberList(key: String): List<Long> =
    (field(key) as JsonValue.Arr).items.map {
        (it as? JsonValue.Num)?.value
            ?: throw IllegalStateException("field '$key' is not a number array: $fields")
    }

fun JsonValue.Obj.stringList(key: String): List<String> =
    (field(key) as JsonValue.Arr).items.map {
        (it as? JsonValue.Str)?.value
            ?: throw IllegalStateException("field '$key' is not a string array: $fields")
    }
