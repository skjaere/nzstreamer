package io.skjaere.usenet.serve

/** A small JSON reader and writer for the spike's control and log (test code; the module has no JSON library). */
object Json {
    /** [text] as maps, lists, Doubles, Strings, Booleans and nulls. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.space()
        require(reader.at == text.length) { "more after the value, at ${reader.at}" }
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> {
                out.append('"')
                for (c in value) {
                    when {
                        c == '"' -> out.append("\\\"")
                        c == '\\' -> out.append("\\\\")
                        c < ' ' -> out.append("\\u%04x".format(c.code))
                        else -> out.append(c)
                    }
                }
                out.append('"')
            }
            is Double -> if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) out.append(value.toLong()) else out.append(value)
            is Number, is Boolean -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    write(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> write(value.toString(), out)
        }
    }

    private class Reader(val text: String) {
        var at = 0

        fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            space()
            require(at < text.length) { "no value at $at" }
            return when (val c = text[at]) {
                '{' -> map()
                '[' -> list()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("'$c' at $at")
            }
        }

        private fun map(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            space()
            if (peek() == '}') return map.also { at++ }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                map[key] = value()
                space()
                if (peek() == ',') at++ else return map.also { expect('}') }
            }
        }

        private fun list(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            space()
            if (peek() == ']') return list.also { at++ }
            while (true) {
                list += value()
                space()
                if (peek() == ',') at++ else return list.also { expect(']') }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = text[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> out.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDouble()
        }

        private fun word(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "not $word at $at" }
            at += word.length
            return value
        }

        private fun peek(): Char = if (at < text.length) text[at] else throw IllegalArgumentException("the text ends at $at")

        private fun expect(c: Char) {
            require(peek() == c) { "'$c' wanted at $at" }
            at++
        }
    }
}
