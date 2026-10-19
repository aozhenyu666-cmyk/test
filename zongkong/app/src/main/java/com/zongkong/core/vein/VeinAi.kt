package com.zongkong.core.vein

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 脉络和 AI：拆分（材料 → 候选节点和关系）、打包（只发相关子结构）、推演（结构先行的回答）。
 * 提示词和「脉络」App 一致。
 */
object VeinAi {

    /** 中文 1 字 ≈ 1 token，其他 3.5 字符 ≈ 1 token（偏保守）。 */
    fun tokens(s: String): Int {
        val cjk = s.count { it in '⺀'..'鿿' || it in '豈'..'﫿' || it in '＀'..'￯' || it in '　'..'〿' }
        return kotlin.math.ceil(cjk + (s.length - cjk) / 3.5).toInt()
    }

    // ---------- 拆分 ----------

    val PROMPT_SPLIT = """
        你是结构拆分器。把下面的材料拆成节点和关系，只输出 JSON，不要任何其他文字。
        规则：
        1. 每个节点只含一句结论，最多 12 个节点。
        2. 每个节点给 3-5 个激活词，选最能唤起相关知识的专业词。
        3. 关系类型只能是：属于、导致、支撑、反例、对立、前置、类似。每条关系附一句理由。
        4. 材料中没有证据的推断，status 设为"假设"，并给出 verify（一句验证方式）。
        5. 已有节点列表见下方；含义相同的，用 merge_into 指向已有 id，不新建。
        6. type 只能是：概念、论断、规则、问题、案例。confidence 为 0-100 的整数。
        7. parent 填层级名（可空），从“已有层级”里选；都不合适就新起一个短名。
        格式：
        {"nodes":[{"tmp_id","title","type","claim","keywords","status","confidence","verify","merge_into","parent"}],
         "links":[{"from","to","type","why"}]}
        links 的 from / to 填 tmp_id 或已有节点 id。
    """.trimIndent()

    fun splitUser(v: Vein, system: String, input: String): String {
        val existing = v.list(system).ifEmpty { v.nodes.take(60) }
        val list = if (existing.isEmpty()) "（无）" else existing.joinToString("\n") { "${it.id} ${it.title}" }
        val levels = v.system(system)?.levels.orEmpty().joinToString("、").ifBlank { "（无）" }
        return "已有层级：$levels\n已有节点：\n$list\n\n材料：\n$input"
    }

    /** 从模型回复里抠出 JSON（容忍 ```、尾逗号、注释、中文引号）。 */
    fun extractJson(text: String): JsonObject? {
        val t = text.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val body = t.substring(a, b + 1)
        fun tidy(x: String) = x.replace(Regex("(?m)^\\s*//.*$"), "").replace(Regex(",\\s*([}\\]])"), "$1")
        for (attempt in listOf(body, tidy(body), tidy(body.replace('“', '"').replace('”', '"')))) {
            runCatching { com.zongkong.core.Net.parseObject(attempt) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull?.trim() ?: (this?.toString()?.trim('"') ?: "")

    /** 模型的拆分结果 → 候选卡片和候选关系（自造的关系类型直接丢掉）。 */
    fun parseSplit(json: JsonObject, system: String, source: String, prefix: String): Pair<List<Candidate>, List<CandLink>> {
        val idMap = mutableMapOf<String, String>()
        val nodes = (json["nodes"] as? JsonArray).orEmpty().take(12).mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val title = o["title"].str().take(40)
            if (title.isEmpty()) return@mapIndexedNotNull null
            val raw = o["tmp_id"].str().ifEmpty { o["id"].str() }.ifEmpty { "t${i + 1}" }
            val tmp = "$prefix-$raw"
            idMap[raw] = tmp
            val kw = when (val k = o["keywords"]) {
                is JsonArray -> k.map { it.str() }
                null -> emptyList()
                else -> k.str().split(Regex("[,，、]"))
            }.map { it.trim() }.filter { it.isNotEmpty() }.take(5)
            Candidate(
                tmp = tmp, title = title,
                type = NodeType.of(o["type"].str()).label,
                claim = o["claim"].str().ifEmpty { title },
                keywords = kw,
                status = VStatus.of(o["status"].str()).label,
                confidence = ((o["confidence"] as? JsonPrimitive)?.intOrNull ?: o["confidence"].str().removeSuffix("%").toIntOrNull() ?: 50).coerceIn(0, 100),
                verify = o["verify"].str(),
                mergeInto = o["merge_into"].str(),
                system = system,
                parent = o["parent"].str().split('/').take(3).joinToString("/"),
                source = source,
            )
        }
        val links = (json["links"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o["type"].str()
            if (type !in Rel.TYPES) return@mapNotNull null
            val from = o["from"].str().let { idMap[it] ?: it }
            val to = o["to"].str().let { idMap[it] ?: it }
            if (from.isEmpty() || to.isEmpty() || from == to) null else CandLink(from, to, type, o["why"].str())
        }
        return nodes to links
    }

    // ---------- 打包 ----------

    data class PackItem(val key: String, val kind: String, val full: String, val short: String = full, val fixed: Boolean = false, val prio: Int = 9, var state: String = "full")
    data class Pack(val items: List<PackItem>, val text: String, val tokens: Int, val budget: Int, val libraryTokens: Int)

    private val PRIORITY = mapOf("对立" to 1, "in导致" to 2, "反例" to 3, "支撑" to 4, "out导致" to 5, "前置" to 6, "类似" to 7, "属于" to 8)

    private fun label(r: Relation): String {
        val out = r.dir == "out"
        return when (r.type) {
            Rel.CAUSE -> if (out) "下游·本节点导致" else "上游·导致本节点"
            Rel.SUPPORT -> if (out) "本节点支撑" else "支撑本节点"
            Rel.COUNTER -> if (out) "本节点削弱" else "反例·削弱本节点"
            Rel.RIVAL -> "对立·竞争解释"
            Rel.PRE -> if (out) "本节点是它的前置" else "前置·先要有"
            Rel.LIKE -> "类似"
            Rel.PART -> if (out) "本节点属于" else "下属"
            else -> r.type
        }
    }

    val MODE_QUESTIONS = mapOf(
        "extend" to "请沿着这个节点延展：它的上游根因和下游后果还缺什么？给出 3 个最值得补进结构的新节点或关系。",
        "verify" to "这个节点还是假设。帮我设计一个本周就能做、能区分真假的小验证：做什么、记什么、怎样算成立。",
    )

    /**
     * 只发相关子结构：路径、节点、一跳邻居、激活词、（因果链）、问题。超预算时：
     * ① 邻居只留标题 ② 路径只留最后两层 ③ 去掉低优先级邻居 ④ 激活词只留本节点的。节点本身和问题永不砍。
     */
    fun pack(v: Vein, id: String, question: String, budget: Int = 1500, removed: Set<String> = emptySet(), chain: List<String> = emptyList()): Pack? {
        val n = v.node(id) ?: return null
        val items = mutableListOf<PackItem>()
        val path = v.pathOf(n)
        items += PackItem("path", "路径", "【路径】" + path.joinToString(" › "), "【路径】… › " + path.takeLast(2).joinToString(" › "))
        val meta = listOfNotNull(n.type, n.status, n.confidence?.let { "把握$it%" }).joinToString("｜")
        items += PackItem("node", "节点", buildString {
            append("【节点】${n.title}（$meta）\n${n.claim}")
            if (n.note.isNotBlank()) append("\n${n.note}")
            if (n.verify.isNotBlank()) append("\n验证方式：${n.verify}")
            n.checks.lastOrNull()?.let { append("\n上次回填（${it.date}）：${it.result}") }
        }, fixed = true)
        val rels = v.relations(id).distinctBy { it.dir + it.type + it.other.id }
            .sortedBy { PRIORITY[it.type] ?: PRIORITY[it.dir + it.type] ?: 9 }
        rels.forEach { r ->
            val p = PRIORITY[r.type] ?: PRIORITY[r.dir + r.type] ?: 9
            items += PackItem("nb:${r.dir}${r.type}${r.other.id}", "邻居", "- ${label(r)}｜${r.other.title}：${r.other.claim}", "- ${label(r)}｜${r.other.title}", prio = p)
        }
        if (chain.size > 1) items += PackItem("chain", "因果链", "【因果链】" + chain.joinToString(" → "), "【因果链】" + chain.takeLast(3).joinToString(" → "))
        val own = n.keywords
        val all = (own + rels.flatMap { it.other.keywords }).distinct()
        items += PackItem("kw", "激活词", "【激活词】" + all.joinToString("、"), "【激活词】" + own.joinToString("、"))
        val rivals = rels.filter { it.type == Rel.RIVAL }.map { it.other.title }.distinct()
        if (rivals.isNotEmpty()) items += PackItem("rival", "对立提示", "【要求】本节点与「${rivals.joinToString("」「")}」是对立关系。请说明怎样区分这两个解释。", fixed = true)
        items += PackItem("q", "问题", "问题：" + question.ifBlank { "（未填写）" }, fixed = true)

        items.forEach { it.state = if (it.key in removed && !it.fixed) "removed" else "full" }
        fit(items, budget)
        val shown = items.filter { it.state == "full" || it.state == "short" }
        var text = shown.joinToString("\n") { if (it.state == "short") it.short else it.full }
        if (shown.any { it.kind == "邻居" }) text = text.replaceFirst(Regex("(?m)^- "), "【邻居】\n- ")
        return Pack(items, text, tokens(text), budget, libraryTokens(v))
    }

    private fun cost(items: List<PackItem>) = tokens(items.filter { it.state == "full" || it.state == "short" }.joinToString("\n") { if (it.state == "short") it.short else it.full })

    private fun fit(items: List<PackItem>, budget: Int) {
        val nbs = items.filter { it.kind == "邻居" && it.state == "full" }.sortedByDescending { it.prio }
        for (it in nbs) { if (cost(items) <= budget) return; it.state = "short" }
        items.firstOrNull { it.key == "path" && it.state == "full" }?.let { if (cost(items) > budget) it.state = "short" }
        items.firstOrNull { it.key == "chain" && it.state == "full" }?.let { if (cost(items) > budget) it.state = "short" }
        for (it in nbs) { if (cost(items) <= budget) return; it.state = "dropped" }
        items.firstOrNull { it.key == "kw" && it.state == "full" }?.let { if (cost(items) > budget) it.state = "short" }
    }

    /** 把整库都发出去要多少 token（对比打包省了多少）。 */
    fun libraryTokens(v: Vein): Int = tokens(v.nodes.joinToString("\n") { n ->
        val rels = n.links.joinToString("；") { l -> l.type + "→" + (v.node(l.to)?.title ?: l.to) }
        v.pathOf(n).joinToString(" › ") + "（${n.type}｜${n.status}）" + n.claim + " " + n.note + " 激活词：" + n.keywords.joinToString("、") +
            (if (n.verify.isNotBlank()) " 验证：" + n.verify else "") + (if (rels.isNotBlank()) " 关系：$rels" else "")
    })

    /** 体系大纲（Markdown）：层级、结论、激活词、关系。 */
    fun outline(v: Vein, system: String): String {
        val sys = v.system(system)
        val lines = mutableListOf("# $system")
        fun parentOf(l: String) = if (l.lastIndexOf('/') < 0) "" else l.substring(0, l.lastIndexOf('/'))
        fun walk(parent: String, depth: Int) {
            v.list(system).filter { it.parent == parent }.forEach { n ->
                val rels = n.links.joinToString("，") { l -> l.type + "→" + (v.node(l.to)?.title ?: l.to) }
                lines += "  ".repeat(depth) + "- **${n.title}**（${n.type}·${n.status}${n.confidence?.let { "·$it%" } ?: ""}）：${n.claim}" +
                    (if (n.keywords.isNotEmpty()) " 〔${n.keywords.joinToString("、")}〕" else "") + (if (rels.isNotBlank()) " ｛$rels｝" else "")
            }
            sys?.levels.orEmpty().filter { parentOf(it) == parent }.forEach { lv ->
                lines += "  ".repeat(depth) + "- " + lv.substringAfterLast('/')
                walk(lv, depth + 1)
            }
        }
        walk("", 0)
        return lines.joinToString("\n")
    }

    // ---------- 推演 ----------

    val PROMPT_THINK = """
        下面是我的知识结构片段：路径、节点、邻居和激活词。请先用激活词定位相关知识，再回答问题。
        回答前在内部完成：列出要点、判断上下游与并列关系、找出唯一核心。不要输出过程。
        只输出：
        - 核心判断：一句话
        - 结构：上游 → 下游（最多 5 个节点）
        - 另一种可能：一个竞争解释
        - 如果我错了，最可能错在哪
        - 把握：几成
        - 验证：一个本周能做的小动作
        - 建议新增：最多 3 个节点或关系（标题 + 一句结论 + 关系类型）
        禁止逐条分析每个要点。
        「建议新增」每条单独一行，格式：关系类型｜标题｜一句结论｜3 个激活词（顿号分隔）。关系类型是新节点对当前节点的关系，只能用：属于、导致、支撑、反例、对立、前置、类似。
    """.trimIndent()

    data class Suggestion(val type: String, val title: String, val claim: String, val keywords: List<String>)
    data class Answer(
        val core: String, val structure: String, val alt: String, val wrong: String, val confidence: String,
        val verify: String, val suggest: List<Suggestion>, val confidencePct: Int?, val raw: String,
    ) {
        val ok: Boolean get() = core.isNotBlank()
    }

    private val SECTIONS = listOf(
        "core" to Regex("^核心判断"), "structure" to Regex("^结构"), "alt" to Regex("^另一种可能"), "wrong" to Regex("^如果我错了"),
        "confidence" to Regex("^把握"), "verify" to Regex("^验证"), "suggest" to Regex("^建议新增"),
    )

    fun parseAnswer(text: String): Answer {
        val buf = mutableMapOf<String, MutableList<String>>()
        var cur: String? = null
        for (rawLine in text.lines()) {
            val line = rawLine.replace("**", "").replace("__", "").replace(Regex("^\\s*#{1,6}\\s*"), "")
                .replace(Regex("^\\s*(?:[-*•]|\\d+[.、)])\\s*"), "").trim()
            if (line.isEmpty()) continue
            val sec = SECTIONS.firstOrNull { it.second.containsMatchIn(line) }
            if (sec != null && Regex("^[^：:]{0,16}[：:]").containsMatchIn(line)) {
                cur = sec.first
                buf[cur] = listOfNotNull(line.replace(Regex("^[^：:]*[：:]\\s*"), "").takeIf { it.isNotBlank() }).toMutableList()
            } else if (sec != null && line.length < 16) {
                cur = sec.first
                buf[cur] = mutableListOf()
            } else if (cur != null) buf.getOrPut(cur) { mutableListOf() } += line
        }
        fun s(k: String) = buf[k].orEmpty().joinToString(" ").trim()
        val conf = s("confidence")
        val pct = Regex("(\\d{1,3})\\s*%").find(conf)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("([一二三四五六七八九十\\d])\\s*成").find(conf)?.groupValues?.get(1)?.let { c -> "零一二三四五六七八九十".indexOf(c).takeIf { it >= 0 } ?: c.toIntOrNull() }?.times(10)
        val suggest = buf["suggest"].orEmpty().mapNotNull { parseSuggestion(it) }.take(3)
        return Answer(s("core"), s("structure"), s("alt"), s("wrong"), conf, s("verify"), suggest, pct, text)
    }

    private fun parseSuggestion(line: String): Suggestion? {
        if (Regex("^(无|没有|暂无)").containsMatchIn(line)) return null
        val parts = line.split(Regex("\\s*[｜|]\\s*")).filter { it.isNotBlank() }.toMutableList()
        var keywords = emptyList<String>()
        if (parts.size >= 4) keywords = parts.removeAt(parts.lastIndex).split(Regex("[、,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.take(5)
        fun clean(t: String) = t.replace(Regex("[\\[\\]【】（）()]"), "").trim()
        var type: String
        var title: String
        var claim: String
        if (parts.size >= 3) {
            type = parts[0]; title = parts[1]; claim = parts.drop(2).joinToString("，")
            if (clean(type) !in Rel.TYPES) {
                parts.firstOrNull { clean(it) in Rel.TYPES }?.let { t ->
                    type = t
                    val rest = parts.filter { it != t }
                    title = rest.getOrElse(0) { "" }; claim = rest.getOrElse(1) { "" }
                }
            }
        } else {
            val m = Regex("^(.+?)[：:](.+)$").find(line) ?: return null
            title = m.groupValues[1]; claim = m.groupValues[2].replace(Regex("[（(][^）)]*[）)]\\s*$"), "")
            type = Rel.TYPES.firstOrNull { line.contains(it) } ?: Rel.LIKE
        }
        type = clean(type).takeIf { it in Rel.TYPES } ?: Rel.TYPES.firstOrNull { line.contains(it) } ?: Rel.LIKE
        title = title.replace(Regex("^(标题|节点)[：:]"), "").trim().take(40)
        claim = claim.replace(Regex("^结论[：:]"), "").trim()
        return if (title.isNotEmpty()) Suggestion(type, title, claim, keywords) else null
    }

    /** “建议新增”转成候选卡片：新节点对当前节点的关系也一起变成候选关系。 */
    fun suggestionsToCandidates(v: Vein, nodeId: String, s: List<Suggestion>, prefix: String): Pair<List<Candidate>, List<CandLink>> {
        val n = v.node(nodeId) ?: return emptyList<Candidate>() to emptyList()
        val cands = s.mapIndexed { i, x ->
            Candidate("$prefix-s${i + 1}", x.title, NodeType.CLAIM.label, x.claim.ifBlank { x.title }, x.keywords, VStatus.HYPO.label, 50, "", "", n.system, n.parent, "推演建议")
        }
        val links = cands.zip(s).map { (c, x) -> CandLink(c.tmp, nodeId, x.type, "推演时建议") }
        return cands to links
    }
}
