package com.zongkong.core.vein

/**
 * 和「脉络」App 一样的文件格式：一个节点 = 一个 Markdown 文件，YAML 头 + 一句结论 + 说明 + “## 关系”（双链）。
 * 文件夹就是一个 Obsidian 仓库：体系/体系.md、体系/层级/标题.md、.mailuo/review.json。
 *
 * YAML 只认脉络写出来的那几种写法（标量、[a, b]、- {k: v} 列表、- 项目），够读写脉络和 Obsidian Properties。
 */
object VeinMd {
    private val FIELD_ORDER = listOf("id", "title", "type", "system", "parent", "keywords", "status", "confidence", "verify", "source", "links", "checks", "created", "updated")

    fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|#^\[\]]"""), "·").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "未命名" }

    fun nodePath(n: VNode): String =
        (listOf(safeName(n.system)) + n.parent.split('/').filter { it.isNotBlank() }.map(::safeName) + (safeName(n.title) + ".md")).joinToString("/")

    fun systemPath(name: String) = "${safeName(name)}/${safeName(name)}.md"

    // ---------- 写 ----------

    private val NEEDS_QUOTE_START = Regex("""^[\s\-?:,\[\]{}#&*!|>'"%@`]""")
    private val NEEDS_QUOTE_ANY = Regex("""[:#,\[\]{}"\n]|\s$""")
    private val LOOKS_SPECIAL = Regex("""^(true|false|null|yes|no|~|[-+]?[\d.]+)$""", RegexOption.IGNORE_CASE)

    fun q(v: Any?): String = when (v) {
        null -> "\"\""
        is Number, is Boolean -> v.toString()
        else -> {
            val s = v.toString()
            if (s.isEmpty() || NEEDS_QUOTE_START.containsMatchIn(s) || NEEDS_QUOTE_ANY.containsMatchIn(s) || LOOKS_SPECIAL.matches(s)) json(s) else s
        }
    }

    private fun json(s: String) = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun flowList(a: List<String>) = a.joinToString(", ", "[", "]") { q(it) }
    private fun flowMap(m: List<Pair<String, Any?>>) = m.filter { (_, v) -> v != null && v != "" }.joinToString(", ", "{", "}") { (k, v) -> "$k: ${q(v)}" }

    fun serializeNode(n: VNode, titleOf: (String) -> String?): String {
        val lines = mutableListOf("---")
        FIELD_ORDER.forEach { k ->
            when (k) {
                "id" -> lines += "id: ${q(n.id)}"
                "title" -> lines += "title: ${q(n.title)}"
                "type" -> lines += "type: ${q(n.type)}"
                "system" -> lines += "system: ${q(n.system)}"
                "parent" -> if (n.parent.isNotBlank()) lines += "parent: ${q(n.parent)}"
                "keywords" -> lines += "keywords: ${flowList(n.keywords)}"
                "status" -> lines += "status: ${q(n.status)}"
                "confidence" -> n.confidence?.let { lines += "confidence: $it" }
                "verify" -> if (n.verify.isNotBlank()) lines += "verify: ${q(n.verify)}" else if (n.status == VStatus.HYPO.label) lines += "verify: \"\""
                "source" -> if (n.source.isNotBlank()) lines += "source: ${q(n.source)}"
                "links" -> if (n.links.isNotEmpty()) {
                    lines += "links:"
                    n.links.forEach { lines += "  - " + flowMap(listOf("type" to it.type, "to" to it.to, "why" to it.why)) }
                }
                "checks" -> if (n.checks.isNotEmpty()) {
                    lines += "checks:"
                    n.checks.forEach { lines += "  - " + flowMap(listOf("date" to it.date, "result" to it.result, "status" to it.status, "confidence" to it.confidence)) }
                }
                "created" -> if (n.created.isNotBlank()) lines += "created: ${n.created}"
                "updated" -> if (n.updated.isNotBlank()) lines += "updated: ${n.updated}"
            }
        }
        if (n.thread.isNotBlank()) lines += "zongkong_thread: ${q(n.thread)}"
        lines += "---"
        lines += n.claim
        if (n.note.isNotBlank()) { lines += ""; lines += n.note }
        val rels = n.links.map { l -> "- ${l.type} [[${titleOf(l.to) ?: l.to}]]" + if (l.why.isNotBlank()) " — ${l.why}" else "" }
        if (rels.isNotEmpty()) { lines += ""; lines += "## 关系"; lines += rels }
        return lines.joinToString("\n") + "\n"
    }

    fun serializeSystem(s: VSystem, nodes: List<VNode>, date: String): String {
        val lines = mutableListOf("---", "kind: system", "name: ${q(s.name)}", "levels: ${flowList(s.levels)}", "created: ${s.created.ifBlank { date }}", "---")
        lines += s.desc.ifBlank { "「${s.name}」知识体系。由脉络 App 维护，可在 Obsidian 中直接打开。" }
        lines += ""
        lines += "## 结构"
        fun parentOf(l: String) = if (l.lastIndexOf('/') < 0) "" else l.substring(0, l.lastIndexOf('/'))
        fun render(parent: String, depth: Int) {
            nodes.filter { it.parent == parent }.forEach { lines += "  ".repeat(depth) + "- [[${it.title}]]" }
            s.levels.filter { parentOf(it) == parent }.forEach { lv ->
                lines += "  ".repeat(depth) + "- " + lv.substringAfterLast('/')
                render(lv, depth + 1)
            }
        }
        render("", 0)
        return lines.joinToString("\n") + "\n"
    }

    /** 整个库导出成 {路径: 内容}。 */
    fun export(v: Vein, date: String): Map<String, String> = buildMap {
        val titles = v.nodes.associate { it.id to it.title }
        v.systems.forEach { s -> put(systemPath(s.name), serializeSystem(s, v.list(s.name), date)) }
        v.nodes.forEach { n -> put(nodePath(n), serializeNode(n, titles::get)) }
        put(".mailuo/review.json", reviewJson(v.review))
    }

    private fun reviewJson(r: Map<String, Recall>) = r.entries.joinToString(",", "{", "}") { (k, x) ->
        "${json(k)}:{\"step\":${x.step},\"due\":${json(x.due)},\"last\":${json(x.last)},\"n\":${x.n}}"
    }

    // ---------- 读 ----------

    /** 拆出 YAML 头和正文。没有头返回 null。 */
    fun frontmatter(text: String): Pair<Map<String, Any?>, String>? {
        val m = Regex("""^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$""").find(text.removePrefix("﻿")) ?: return null
        return yaml(m.groupValues[1]) to m.groupValues[2]
    }

    /** 迷你 YAML：顶层 key: value；value 可以是标量、[流式列表]、或下面缩进的 - 列表（元素是 {流式映射} 或标量）。 */
    fun yaml(src: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        var listKey: String? = null
        for (raw in src.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indented = raw.startsWith(" ") || raw.startsWith("\t")
            val t = raw.trim()
            if (indented && t.startsWith("- ") && listKey != null) {
                @Suppress("UNCHECKED_CAST")
                val list = (out[listKey] as? MutableList<Any?>) ?: mutableListOf<Any?>().also { out[listKey!!] = it }
                list += value(t.removePrefix("- ").trim())
                continue
            }
            val colon = keyColon(t)
            if (colon < 0) continue
            val k = t.substring(0, colon).trim()
            val rest = t.substring(colon + 1).trim()
            if (rest.isEmpty()) {
                out[k] = mutableListOf<Any?>()
                listKey = k
            } else {
                out[k] = value(rest)
                listKey = null
            }
        }
        return out
    }

    private fun keyColon(t: String): Int {
        val i = t.indexOf(':')
        return if (i > 0 && (i == t.length - 1 || t[i + 1] == ' ')) i else -1
    }

    private fun value(s: String): Any? = when {
        s.startsWith("[") && s.endsWith("]") -> splitFlow(s.substring(1, s.length - 1)).map { scalar(it) }
        s.startsWith("{") && s.endsWith("}") -> splitFlow(s.substring(1, s.length - 1)).mapNotNull { part ->
            val c = keyColon(part.trim()).takeIf { it > 0 } ?: part.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            part.trim().substring(0, c).trim() to scalar(part.trim().substring(c + 1).trim())
        }.toMap()
        else -> scalar(s)
    }

    /** 按逗号拆流式集合，引号和括号里的逗号不拆。 */
    private fun splitFlow(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> {
                    cur.append(c)
                    if (c == '\\' && quote == '"' && i + 1 < s.length) { cur.append(s[i + 1]); i++ } else if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> { quote = c; cur.append(c) }
                c == '[' || c == '{' -> { depth++; cur.append(c) }
                c == ']' || c == '}' -> { depth--; cur.append(c) }
                c == ',' && depth == 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private fun scalar(s: String): Any? {
        val t = s.trim()
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) return unjson(t.substring(1, t.length - 1))
        if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) return t.substring(1, t.length - 1).replace("''", "'")
        if (t == "null" || t == "~" || t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        return t
    }

    private fun unjson(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'u' -> if (i + 5 < s.length) { s.substring(i + 2, i + 6).toIntOrNull(16)?.let { append(it.toChar()) }; i += 4 }
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun str(v: Any?) = v?.toString()?.trim().orEmpty()

    /** 读一个节点文件；不是脉络节点（没有 id，或者没有脉络字段）返回 null。 */
    fun parseNode(text: String, path: String): VNode? {
        val (meta, body) = frontmatter(text) ?: return null
        if (meta["id"] == null || meta["kind"] == "system") return null
        if (!("keywords" in meta || "status" in meta || "links" in meta)) return null
        val main = body.split(Regex("""\n##\s*关系\s*\n""")).first().trim()
        val paras = main.split(Regex("""\n\s*\n"""))
        val kw = when (val k = meta["keywords"]) {
            is List<*> -> k.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            null -> emptyList()
            else -> k.toString().split(Regex("[,，、]\\s*")).filter { it.isNotBlank() }
        }
        @Suppress("UNCHECKED_CAST")
        val links = (meta["links"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>) }.mapNotNull { m ->
            val to = str(m["to"]).ifEmpty { return@mapNotNull null }
            VLink(str(m["type"]), to, str(m["why"]))
        }
        @Suppress("UNCHECKED_CAST")
        val checks = (meta["checks"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>) }.map { m ->
            VCheck(str(m["date"]), str(m["result"]), str(m["status"]), str(m["confidence"]).toIntOrNull())
        }
        return VNode(
            id = str(meta["id"]),
            title = str(meta["title"]).ifEmpty { path.substringAfterLast('/').removeSuffix(".md") },
            type = str(meta["type"]).ifEmpty { NodeType.CLAIM.label },
            system = str(meta["system"]).ifEmpty { path.substringBefore('/') },
            parent = str(meta["parent"]),
            keywords = kw,
            status = str(meta["status"]).ifEmpty { VStatus.HYPO.label },
            confidence = str(meta["confidence"]).removeSuffix("%").toIntOrNull(),
            verify = str(meta["verify"]),
            source = str(meta["source"]),
            links = links,
            checks = checks,
            created = str(meta["created"]),
            updated = str(meta["updated"]),
            claim = paras.firstOrNull().orEmpty().trim(),
            note = paras.drop(1).joinToString("\n\n").trim(),
            thread = str(meta["zongkong_thread"]),
        )
    }

    fun parseSystem(text: String): VSystem? {
        val (meta, body) = frontmatter(text) ?: return null
        if (meta["kind"] != "system") return null
        val levels = (meta["levels"] as? List<*>).orEmpty().mapNotNull { it?.toString() }
        return VSystem(str(meta["name"]), levels, body.split(Regex("""\n##""")).first().trim(), str(meta["created"]))
    }

    /** 读一批文件（路径 → 内容），合并进库：同 id 的以文件为准，新体系、新节点加进来。返回 (新库, 读到几个节点)。 */
    fun import(v: Vein, files: Map<String, String>, date: String): Pair<Vein, Int> {
        var w = v
        var count = 0
        files.filterKeys { it.endsWith(".md") }.forEach { (path, text) ->
            parseSystem(text)?.let { s ->
                w = if (w.system(s.name) == null) w.copy(systems = w.systems + s) else w.copy(systems = w.systems.map { if (it.name == s.name) it.copy(levels = (it.levels + s.levels).distinct(), desc = s.desc.ifBlank { it.desc }) else it })
                return@forEach
            }
            parseNode(text, path)?.let { n ->
                w = Veins.put(w, n.copy(updated = n.updated), n.updated.ifBlank { date })
                count++
            }
        }
        // 编号续上，免得新建的撞号
        val maxSeq = w.nodes.mapNotNull { Regex("""n-(\d+)""").matchEntire(it.id)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return w.copy(seq = maxOf(w.seq, maxSeq)) to count
    }
}
