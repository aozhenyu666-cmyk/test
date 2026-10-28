package com.zongkong.sentinel.watch

/**
 * 极小的 JSON 读写（纯 Kotlin，不依赖 org.json）。
 *
 * 为什么要自己写：`:watch` 里的策略文件、健康快照、事件进度文件都要原子落盘，
 * 而这些编解码必须是**纯 JVM 可测**的（本机没有真机，只能靠单测推进时间做故障注入）。
 * `org.json` 在 Android 单元测试里是空壳桩，调用就抛 "not mocked"。
 *
 * 只支持本协议用到的类型：对象 / 数组 / 字符串 / 整数 / 浮点 / 布尔 / null。
 * 不追求通用性，字段顺序保持插入顺序，方便比对文件内容。
 */
object MiniJson {

    class ParseError(message: String) : IllegalArgumentException(message)

    fun obj(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in pairs) m[k] = v
        return m
    }

    fun encode(v: Any?): String {
        val sb = StringBuilder()
        write(v, sb)
        return sb.toString()
    }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Short -> sb.append(v.toString())
            is Double -> sb.append(if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString())
            is Float -> write(v.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k?.toString() ?: "", sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (value in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(value, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.asIterable(), sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(String.format("%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = P(text)
        val v = p.value()
        p.skipWs()
        if (!p.end()) throw ParseError("多余内容：位置 ${p.pos}")
        return v
    }

    private class P(val s: String) {
        var pos = 0

        fun end() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            if (end()) throw ParseError("意外结束")
            return when (val c = s[pos]) {
                '{' -> mapped()
                '[' -> list()
                '"' -> string()
                't', 'f' -> literal()
                'n' -> if (s.startsWith("null", pos)) { pos += 4; null } else throw ParseError("非法字面量")
                else -> if (c == '-' || c.isDigit()) number() else throw ParseError("非法字符 '$c'：位置 $pos")
            }
        }

        private fun mapped(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (!end() && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (end() || s[pos] != '"') throw ParseError("对象键必须是字符串：位置 $pos")
                val k = string()
                skipWs()
                if (end() || s[pos] != ':') throw ParseError("缺少 ':'：位置 $pos")
                pos++
                m[k] = value()
                skipWs()
                if (end()) throw ParseError("对象没有闭合")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw ParseError("对象里意外的 '${s[pos]}'：位置 $pos")
                }
            }
        }

        private fun list(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (!end() && s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                if (end()) throw ParseError("数组没有闭合")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseError("数组里意外的 '${s[pos]}'：位置 $pos")
                }
            }
        }

        private fun string(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (end()) throw ParseError("字符串没有闭合")
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (end()) throw ParseError("转义没有闭合")
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(pos + 1, minOf(pos + 5, s.length))
                                if (hex.length < 4) throw ParseError("\\u 转义不完整")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseError("不认识的转义 '\\$e'")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun literal(): Any = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> throw ParseError("非法字面量：位置 $pos")
        }

        private fun number(): Any {
            val start = pos
            if (!end() && s[pos] == '-') pos++
            while (!end() && s[pos].isDigit()) pos++
            var integral = true
            if (!end() && s[pos] == '.') {
                integral = false
                pos++
                while (!end() && s[pos].isDigit()) pos++
            }
            if (!end() && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false
                pos++
                if (!end() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!end() && s[pos].isDigit()) pos++
            }
            val raw = s.substring(start, pos)
            if (raw.isBlank() || raw == "-") throw ParseError("非法数字：位置 $start")
            return if (integral) raw.toLong() else raw.toDouble()
        }
    }

    // ---------- 读字段的便利函数 ----------

    @Suppress("UNCHECKED_CAST")
    fun map(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    fun list(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    fun str(m: Map<String, Any?>, key: String, def: String = ""): String =
        (m[key] as? String) ?: def

    fun nullableStr(m: Map<String, Any?>, key: String): String? = m[key] as? String

    fun lng(m: Map<String, Any?>, key: String, def: Long = 0L): Long = when (val v = m[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: def
        else -> def
    }

    fun int(m: Map<String, Any?>, key: String, def: Int = 0): Int = lng(m, key, def.toLong()).toInt()

    fun bool(m: Map<String, Any?>, key: String, def: Boolean = false): Boolean =
        (m[key] as? Boolean) ?: def

    /** 数组里的字符串集合。 */
    fun strings(v: Any?): List<String> = list(v).mapNotNull { it as? String }
}
