package com.yishou.app.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * 带标签的纯文本：
 *
 *     【主干】……
 *     【要点】
 *     1. ……
 *     2. ……
 *
 * 比 JSON 宽容得多：多一句开场白、少一个引号、包在代码块里都不影响。模型要是偏偏回了 JSON，也照样认。
 * 读出来统一转成 JSONObject（标签名按 [names] 换成英文键，列表键变成数组），后面的校验代码不用改。
 */
object Tagged {

    private val TAG = Regex("^\\s*(?:【\\s*([^】]{1,8})\\s*】|\\[\\s*([^\\]]{1,8})\\s*]|#{1,3}\\s*([\\u4e00-\\u9fa5A-Za-z]{1,8})\\s*$|([\\u4e00-\\u9fa5]{2,6})\\s*[:：])\\s*(.*)$")
    private val ITEM = Regex("^\\s*(?:\\d{1,2}\\s*[.、．)）]|[（(]\\s*\\d{1,2}\\s*[)）]|[①②③④⑤⑥⑦⑧⑨⑩]|[-•·*])\\s*")

    /**
     * 标签 → 段落（按行）。【】和 [] 写法总算标签；“主干：”“## 主干”这种写法只有在 known 里才算，
     * 免得正文里的“因此：”被当成标签。
     */
    fun sections(text: String, known: Set<String> = emptySet()): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trim().removePrefix("```").trim()
            if (line.isEmpty()) continue
            val m = TAG.find(line)
            val name = m?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] } }
            val bracketed = m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())
            if (m != null && name != null && name.isNotBlank() && (bracketed || name.trim() in known)) {
                current = name.trim()
                val rest = m.groupValues[5].trim()
                out.getOrPut(current) { mutableListOf() }.apply { if (rest.isNotEmpty()) add(rest) }
                continue
            }
            current?.let { out.getOrPut(it) { mutableListOf() }.add(line) }
        }
        return out
    }

    /**
     * 读成 JSONObject。names：中文标签 → 英文键；lists：这些键读成数组（每行一条，去掉行首编号）；
     * numbers：这些键读成数（取第一个数）。整段像 JSON 就直接按 JSON 读。
     */
    fun toJson(text: String, names: Map<String, String>, lists: Set<String> = emptySet(), numbers: Set<String> = emptySet()): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start && text.substring(0, start).replace("```", "").replace("json", "").isBlank()) {
            runCatching { return JSONObject(text.substring(start, end + 1)) }
        }
        val o = JSONObject()
        sections(text, names.keys).forEach { (tag, lines) ->
            val key = names[tag] ?: names.entries.firstOrNull { tag.contains(it.key) }?.value ?: return@forEach
            when (key) {
                in lists -> o.put(key, JSONArray(lines.map { it.replace(ITEM, "").trim() }.filter { it.isNotEmpty() }))
                in numbers -> com.yishou.app.think.Words.numbers(lines.joinToString(" ")).firstOrNull()?.let { o.put(key, it) }
                else -> o.put(key, lines.joinToString("\n"))
            }
        }
        return o
    }
}
