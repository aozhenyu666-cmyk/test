package com.yishou.app.bank

/**
 * Word（.docx）里的文字：.docx 是一个压缩包，正文在 word/document.xml 里。
 * 这里只从 XML 里取出段落文字，不经过模型。旧的 .doc 读不了（要先另存为 .docx 或复制文字）。
 */
object DocText {
    private val TOKEN = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:tab\\s*/>|<w:br[^>]*/>|<w:cr\\s*/>|</w:p>")
    private val ENTITY = Regex("&(lt|gt|amp|quot|apos|#\\d+|#x[0-9a-fA-F]+);")

    private val PARA = Regex("<w:p[ >][\\s\\S]*?</w:p>|<w:p/>")
    private val NUM_ID = Regex("<w:numId\\s+w:val=\"(\\d+)\"")
    private val ILVL = Regex("<w:ilvl\\s+w:val=\"(\\d+)\"")
    private val ABSTRACT = Regex("<w:abstractNum\\s[^>]*w:abstractNumId=\"(\\d+)\"[^>]*>([\\s\\S]*?)</w:abstractNum>")
    private val LVL = Regex("<w:lvl\\s[^>]*w:ilvl=\"(\\d+)\"[^>]*>([\\s\\S]*?)</w:lvl>")
    private val NUM = Regex("<w:num\\s[^>]*w:numId=\"(\\d+)\"[^>]*>[\\s\\S]*?<w:abstractNumId\\s+w:val=\"(\\d+)\"")
    private val FMT = Regex("<w:numFmt\\s+w:val=\"([^\"]+)\"")
    private val TEXT = Regex("<w:lvlText\\s+w:val=\"([^\"]*)\"")
    private val START = Regex("<w:start\\s+w:val=\"(\\d+)\"")

    private data class Lvl(val fmt: String, val text: String, val start: Int)

    /**
     * 带自动编号的 Word：题号“1.”、选项“A.”常常是 Word 自己编的，不在正文里。
     * 这里按 numbering.xml 把编号补回每一段前面，拆题才认得出题号和选项。
     */
    fun fromDocx(documentXml: String, numberingXml: String?): String {
        if (numberingXml.isNullOrBlank()) return fromDocumentXml(documentXml)
        val abstracts = ABSTRACT.findAll(numberingXml).associate { a ->
            a.groupValues[1] to LVL.findAll(a.groupValues[2]).associate { l ->
                val body = l.groupValues[2]
                l.groupValues[1].toInt() to Lvl(
                    FMT.find(body)?.groupValues?.get(1) ?: "decimal",
                    TEXT.find(body)?.groupValues?.get(1) ?: "%1.",
                    START.find(body)?.groupValues?.get(1)?.toInt() ?: 1,
                )
            }
        }
        val nums = NUM.findAll(numberingXml).associate { it.groupValues[1] to it.groupValues[2] }
        val counters = mutableMapOf<String, IntArray>()
        val sb = StringBuilder()
        PARA.findAll(documentXml).forEach { p ->
            val xml = p.value
            val numId = NUM_ID.find(xml)?.groupValues?.get(1)
            val abs = numId?.let { nums[it] }
            val levels = abs?.let { abstracts[it] }
            var label = ""
            if (numId != null && numId != "0" && levels != null) {
                val ilvl = ILVL.find(xml)?.groupValues?.get(1)?.toInt() ?: 0
                // 按 numId 计数：每道题的选项常常是一个新的编号实例，从 A 重新数
                val c = counters.getOrPut(numId) { IntArray(9) }
                c[ilvl] = if (c[ilvl] == 0) (levels[ilvl]?.start ?: 1) else c[ilvl] + 1
                for (deeper in ilvl + 1 until 9) c[deeper] = 0
                val lvl = levels[ilvl]
                if (lvl != null && lvl.fmt != "bullet" && lvl.fmt != "none") {
                    label = Regex("%(\\d)").replace(lvl.text) { m ->
                        val k = m.groupValues[1].toInt() - 1
                        format(c.getOrElse(k) { 1 }.coerceAtLeast(1), levels[k]?.fmt ?: "decimal")
                    }.trim()
                }
            }
            val body = fromDocumentXml(xml).trim()
            sb.append(if (label.isNotEmpty()) "$label $body" else body).append('\n')
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private val CN = "零一二三四五六七八九十"

    fun format(n: Int, fmt: String): String = when (fmt) {
        "upperLetter" -> letters(n).uppercase()
        "lowerLetter" -> letters(n)
        "upperRoman" -> roman(n)
        "lowerRoman" -> roman(n).lowercase()
        "chineseCounting", "chineseCountingThousand", "ideographTraditional", "taiwaneseCounting" -> if (n <= 10) CN[n].toString() else if (n < 20) "十" + CN[n - 10] else "$n"
        "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..10) "①②③④⑤⑥⑦⑧⑨⑩"[n - 1].toString() else "$n"
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            val r = (x - 1) % 26
            sb.insert(0, ('a' + r))
            x = (x - 1) / 26
        }
        return sb.toString()
    }

    private fun roman(n: Int): String {
        val v = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n
        val sb = StringBuilder()
        for ((k, s) in v) while (x >= k) { sb.append(s); x -= k }
        return sb.toString()
    }

    fun fromDocumentXml(xml: String): String {
        val sb = StringBuilder()
        TOKEN.findAll(xml).forEach { m ->
            when {
                m.value.startsWith("<w:tab") -> sb.append('\t')
                m.value.startsWith("<w:t") -> sb.append(unescape(m.groupValues[1]))
                else -> sb.append('\n')
            }
        }
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun unescape(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            else -> if (e.startsWith("#x")) e.substring(2).toInt(16).toChar().toString() else e.substring(1).toInt().toChar().toString()
        }
    }

    /** 文字文件的编码：先按 UTF-8 读，乱码多就按 GBK 读（国内很多 txt 是 GBK） */
    fun decode(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        val bad = utf.count { it == '�' }
        if (bad == 0 || bad < utf.length / 200) return utf.removePrefix("﻿")
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf)
    }
}
