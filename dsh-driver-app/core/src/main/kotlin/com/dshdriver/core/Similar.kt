package com.dshdriver.core

/**
 * 字面相似度：判断两句问话是不是"又问了一遍"。
 * 切词思路来自 Mika 的 MemoryIndex/Tokenize（Apache-2.0）：汉字两两相邻成词，英文数字按单词；按本项目风格重写。
 * 只抓字面重复，抓不到"意思一样、说法不同"——已知局限。
 */
object Similar {
    private val CN = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
    private val ORDINAL = Regex("第([一二两三四五六七八九十]{1,3})")

    /** "第三题" → "第3题"。 */
    fun normalizeNumbers(text: String?): String = ORDINAL.replace(text ?: "") { m ->
        val w = m.groupValues[1]
        val n = when {
            w.length == 1 -> CN[w[0]]
            w[0] == '十' -> 10 + (CN[w[1]] ?: 0)
            w.length >= 2 && w[1] == '十' -> (CN[w[0]] ?: 0) * 10 + (if (w.length == 3) CN[w[2]] ?: 0 else 0)
            else -> null
        }
        if (n == null) m.value else "第$n"
    }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    fun terms(text: String?): Set<String> {
        val s = normalizeNumbers(text)
        val out = LinkedHashSet<String>()
        val han = StringBuilder()
        fun flush() {
            if (han.length == 1) out += han.toString()
            for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
            han.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (isHan(c)) { han.append(c); i++; continue }
            flush()
            if (isWord(c)) {
                val start = i
                while (i < s.length && isWord(s[i])) i++
                out += s.substring(start, i).lowercase()
            } else i++
        }
        flush()
        return out
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    fun jaccard(a: String?, b: String?): Double = jaccard(terms(a), terms(b))

    fun maxSimilarity(text: String?, others: List<String>): Double {
        val t = terms(text)
        return others.maxOfOrNull { jaccard(t, terms(it)) } ?: 0.0
    }
}
