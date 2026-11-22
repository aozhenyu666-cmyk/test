package io.github.ccch1mneyyy.dsh_app.fish.guardian.health

/**
 * Pure Kotlin RFC-8259 JSON encoder & parser with typed convenience helpers.
 */
object GuardianJson {
    fun encode(value: Any?): String = StringBuilder().also { appendValue(it, value) }.toString()

    fun map(raw: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return raw as? Map<String, Any?> ?: emptyMap()
    }

    fun list(raw: Any?): List<Any?> = raw as? List<Any?> ?: emptyList()

    fun str(m: Map<String, Any?>, key: String, fallback: String = ""): String =
        m[key] as? String ?: fallback

    fun bool(m: Map<String, Any?>, key: String, fallback: Boolean = false): Boolean =
        m[key] as? Boolean ?: fallback

    fun int(m: Map<String, Any?>, key: String, fallback: Int = 0): Int =
        (m[key] as? Number)?.toInt() ?: fallback

    fun lng(m: Map<String, Any?>, key: String, fallback: Long = 0L): Long =
        (m[key] as? Number)?.toLong() ?: fallback

    fun strList(raw: Any?): List<String> = list(raw).mapNotNull { it as? String }

    fun strMap(raw: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        map(raw).forEach { (k, v) -> if (v is String) out[k] = v }
        return out
    }

    fun intMap(raw: Any?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        map(raw).forEach { (k, v) -> if (v is Number) out[k] = v.toInt() }
        return out
    }

    fun parse(text: String): Any? = Parser(text).parseRoot()

    private fun appendValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(v.toString())
            }
            is String -> appendString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, elem) in v) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, elem)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (elem in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, elem)
                }
                sb.append(']')
            }
            else -> appendString(sb, v.toString())
        }
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (pos != src.length) throw IllegalArgumentException("Trailing JSON at $pos")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++
            skipWs()
            val out = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (!peek('"')) throw IllegalArgumentException("Expected key at $pos")
                val k = parseString()
                skipWs()
                if (!consume(':')) throw IllegalArgumentException("Expected ':' at $pos")
                out[k] = parseValue()
                skipWs()
                if (consume('}')) return out
                if (!consume(',')) throw IllegalArgumentException("Expected ',' at $pos")
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            skipWs()
            val out = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (consume(']')) return out
                if (!consume(',')) throw IllegalArgumentException("Expected ',' at $pos")
            }
        }

        private fun parseString(): String {
            if (!consume('"')) throw IllegalArgumentException("Expected string at $pos")
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                if (ch == '"') return sb.toString()
                if (ch == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val code = src.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$esc")
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val raw = src.substring(start, pos)
            return if (isFloat) raw.toDouble() else {
                val l = raw.toLong()
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
        }

        private fun <T> expectLiteral(lit: String, v: T): T {
            if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit")
            pos += lit.length
            return v
        }

        private fun skipWs() {
            while (pos < src.length && src[pos] in " \t\r\n") pos++
        }

        private fun peek(c: Char): Boolean = pos < src.length && src[pos] == c
        private fun consume(c: Char): Boolean {
            if (!peek(c)) return false
            pos++
            return true
        }
    }
}
