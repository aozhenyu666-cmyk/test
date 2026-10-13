package com.yishou.app.bank

/**
 * Word（.docx）里的文字：.docx 是一个压缩包，正文在 word/document.xml 里。
 * 这里只从 XML 里取出段落文字，不经过模型。旧的 .doc 读不了（要先另存为 .docx 或复制文字）。
 */
object DocText {
    private val TOKEN = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|<w:tab\\s*/>|<w:br[^>]*/>|<w:cr\\s*/>|</w:p>")
    private val ENTITY = Regex("&(lt|gt|amp|quot|apos|#\\d+|#x[0-9a-fA-F]+);")

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
