package app.eikon.gallery.data.backup

/**
 * The little JSON the two servers speak, read and written without a library (the platform's `org.json` cannot be used in the JVM tests, and a library for a dozen fields
 * is more than this needs). Reads objects, arrays, strings (with escapes and `\uXXXX`), numbers, booleans and null into Map, List, String, Double, Boolean and null;
 * anything malformed is an error, never a guess.
 */
internal object MiniJson {
    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        if (!reader.atEnd()) throw ParseException("unexpected text after the value")
        return value
    }

    /** [text] as a JSON string literal, quotes included. */
    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd() = at >= text.length

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            skipSpace()
            if (atEnd()) throw ParseException("unexpected end")
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw ParseException("unexpected '$c' at $at")
            }
        }

        private fun obj(): Map<String, Any?> {
            at++
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') { at++; return map }
            while (true) {
                skipSpace()
                if (peek() != '"') throw ParseException("expected a name at $at")
                val name = string()
                skipSpace()
                expect(':')
                map[name] = value()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw ParseException("expected , or } at ${at - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            at++
            val list = ArrayList<Any?>()
            skipSpace()
            if (peek() == ']') { at++; return list }
            while (true) {
                list += value()
                skipSpace()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw ParseException("expected , or ] at ${at - 1}")
                }
            }
        }

        private fun string(): String {
            at++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(escape())
                    c < ' ' -> throw ParseException("control character in a string")
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (atEnd()) throw ParseException("unterminated escape")
            return when (val e = text[at++]) {
                '"', '\\', '/' -> e
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicode()
                else -> throw ParseException("bad escape \\$e")
            }
        }

        private fun unicode(): Char {
            if (at + HEX > text.length) throw ParseException("short \\u escape")
            val code = text.substring(at, at + HEX).toIntOrNull(RADIX) ?: throw ParseException("bad \\u escape")
            at += HEX
            return code.toChar()
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDoubleOrNull() ?: throw ParseException("bad number at $start")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, at)) throw ParseException("unexpected text at $at")
            at += word.length
            return value
        }

        private fun peek(): Char? = if (atEnd()) null else text[at]

        private fun next(): Char? = if (atEnd()) null else text[at++]

        private fun expect(c: Char) {
            if (next() != c) throw ParseException("expected '$c' at ${at - 1}")
        }
    }

    private const val HEX = 4
    private const val RADIX = 16

    /** Typed reads that say "not there" as null rather than throwing, for answers whose shape a server may change. */
    fun Any?.map(): Map<*, *>? = this as? Map<*, *>
    fun Any?.list(): List<*>? = this as? List<*>
}
