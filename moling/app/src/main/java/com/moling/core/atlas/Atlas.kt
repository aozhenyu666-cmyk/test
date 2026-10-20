package com.moling.core.atlas

import com.moling.core.mind.Tags
import kotlinx.serialization.Serializable

/*
 * 体系图：把散的东西织成网。
 *
 * 和 GPT / Gemini 聊出来的结论、贴进来的长分析、收线的一句话，都不是“记下来”就完了：
 * 墨灵让文字模型把它拆成一条条“论断”（事实 / 判断 / 假设 / 问题 / 方法），再找出论断之间的关系
 * （属于、导致、支撑、反例、对立、前置、类似——和脉络 App 同一套），挂进对应的主题。
 *
 * 然后体系图自己会发现缺口：孤立的点、没验证的假设、没回答的问题、没处理的矛盾、没有前置链的结论。
 * 缺口就是下一次去 GPT / Gemini 要聊的问题——这样每次对话都在补同一张网，而不是聊完就散。
 */

@Serializable
enum class NodeKind(val label: String) { FACT("事实"), JUDGMENT("判断"), HYPOTHESIS("假设"), QUESTION("问题"), METHOD("方法") }

@Serializable
enum class Relation(val label: String) { BELONGS("属于"), CAUSES("导致"), SUPPORTS("支撑"), COUNTER("反例"), OPPOSES("对立"), PREREQ("前置"), SIMILAR("类似") }

@Serializable
data class Topic(val id: String, val name: String, val goal: String = "", val createdAt: Long = 0)

@Serializable
data class Node(
    val id: String,
    val topicId: String,
    val claim: String,
    val kind: NodeKind,
    /** gemini / chatgpt / 用户 / 墨灵。 */
    val source: String,
    /** 高 / 中 / 低。 */
    val confidence: String = "中",
    /** 怎么验证（假设必须有）；验证结果写在 [verified]。 */
    val check: String = "",
    val verified: String = "",
    val createdAt: Long = 0,
    val confirmed: Boolean = false,
)

@Serializable
data class Edge(val from: String, val to: String, val relation: Relation, val note: String = "")

@Serializable
data class Atlas(
    val topics: List<Topic> = emptyList(),
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
)

/** 一个缺口：哪里没织上、为什么、下一次该问什么。 */
data class Gap(val kind: String, val nodeId: String, val why: String, val question: String, val weight: Int)

/** 模型拆出来、还没挂进图里的候选。 */
data class Candidates(val nodes: List<Node>, val edges: List<Edge>)

object AtlasOps {
    fun topic(a: Atlas, name: String, now: Long): Pair<Atlas, Topic> {
        val key = norm(name)
        a.topics.firstOrNull { norm(it.name) == key }?.let { return a to it }
        val t = Topic("t-$now-${a.topics.size}", name.trim().take(30), createdAt = now)
        return a.copy(topics = a.topics + t) to t
    }

    // ------------------------------------------------------------------ 拆

    fun ingestPrompt(topic: Topic, existing: List<Node>, text: String, source: String) = buildString {
        appendLine("【主题】${topic.name}" + if (topic.goal.isNotBlank()) "（目标：${topic.goal}）" else "")
        if (existing.isNotEmpty()) {
            appendLine("【已有论断】（引用时写编号）")
            existing.takeLast(20).forEach { appendLine("${it.id}：${it.claim}") }
        }
        appendLine("【新材料，来自 $source】")
        appendLine(text.take(4000))
        appendLine()
        appendLine("把新材料拆成论断（每条一句话，能单独看懂，不要复述材料），再写出论断之间、以及和已有论断的关系。")
        appendLine("论断类型只用：事实 / 判断 / 假设 / 问题 / 方法。假设必须写怎么验证。关系只用：属于 / 导致 / 支撑 / 反例 / 对立 / 前置 / 类似。")
        appendLine("新论断编号用 n1、n2……；最多 8 条论断、10 条关系。只输出：")
        appendLine("【论断】n1｜判断｜内容｜把握（高/中/低）｜验证方式（没有写无）")
        append("【关系】n1｜导致｜n2｜一句说明")
    }

    fun parse(reply: String, topicId: String, source: String, now: Long): Candidates {
        val map = mutableMapOf<String, String>()
        val nodes = Regex("""【论断】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val tmp = f.getOrNull(0).orEmpty()
            val kind = NodeKind.entries.firstOrNull { it.label == f.getOrNull(1) } ?: return@mapNotNull null
            val claim = f.getOrNull(2).orEmpty().take(80).ifBlank { return@mapNotNull null }
            val id = "n-$now-${map.size}"
            map[tmp] = id
            Node(id, topicId, claim, kind, source, f.getOrNull(3)?.takeIf { it in setOf("高", "中", "低") } ?: "中",
                f.getOrNull(4).orEmpty().takeUnless { it == "无" }.orEmpty().take(60), createdAt = now)
        }.take(8).toList()
        val edges = Regex("""【关系】([^\n]+)""").findAll(reply).mapNotNull { m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val rel = Relation.entries.firstOrNull { it.label == f.getOrNull(1) } ?: return@mapNotNull null
            val from = map[f.getOrNull(0)] ?: f.getOrNull(0)?.takeIf { it.startsWith("n-") } ?: return@mapNotNull null
            val to = map[f.getOrNull(2)] ?: f.getOrNull(2)?.takeIf { it.startsWith("n-") } ?: return@mapNotNull null
            if (from == to) null else Edge(from, to, rel, f.getOrNull(3).orEmpty().take(40))
        }.take(10).toList()
        return Candidates(nodes, edges)
    }

    /**
     * 挂进图里。和已有论断几乎一样的不重复加（关系改挂到旧论断上）；
     * 指向不存在的论断的关系丢掉。返回新的图和真正新增的论断数。
     */
    fun merge(a: Atlas, c: Candidates): Pair<Atlas, Int> {
        val remap = mutableMapOf<String, String>()
        val added = mutableListOf<Node>()
        c.nodes.forEach { n ->
            val dup = (a.nodes + added).firstOrNull { it.topicId == n.topicId && similar(it.claim, n.claim) }
            if (dup != null) remap[n.id] = dup.id else added += n
        }
        val ids = (a.nodes + added).map { it.id }.toSet()
        val edges = c.edges.map { Edge(remap[it.from] ?: it.from, remap[it.to] ?: it.to, it.relation, it.note) }
            .filter { it.from in ids && it.to in ids && it.from != it.to }
            .filter { e -> a.edges.none { it.from == e.from && it.to == e.to && it.relation == e.relation } }
            .distinctBy { Triple(it.from, it.to, it.relation) }
        return a.copy(nodes = a.nodes + added, edges = a.edges + edges) to added.size
    }

    // ------------------------------------------------------------------ 缺口

    /** 找缺口，按重要程度排序。 */
    fun gaps(a: Atlas, topicId: String): List<Gap> {
        val nodes = a.nodes.filter { it.topicId == topicId }
        val ids = nodes.map { it.id }.toSet()
        val edges = a.edges.filter { it.from in ids || it.to in ids }
        val degree = nodes.associate { n -> n.id to edges.count { it.from == n.id || it.to == n.id } }
        val out = mutableListOf<Gap>()
        nodes.forEach { n ->
            when {
                n.kind == NodeKind.HYPOTHESIS && n.verified.isBlank() ->
                    out += Gap("待验证", n.id, "假设还没验证", if (n.check.isNotBlank()) "「${n.claim}」——按「${n.check}」验证了吗？结果是什么？" else "「${n.claim}」——怎样最便宜地验证它？", 5)
                n.kind == NodeKind.QUESTION && edges.none { it.to == n.id && it.relation == Relation.SUPPORTS } ->
                    out += Gap("没回答", n.id, "问题还没有答案", "「${n.claim}」——你现在的答案是什么？依据是哪一条？", 4)
            }
            if ((degree[n.id] ?: 0) == 0 && nodes.size > 1) out += Gap("孤立", n.id, "和别的论断没有任何关系", "「${n.claim}」——它和这个主题里的哪一条有关？是原因、结果还是前提？", 3)
            if (n.kind == NodeKind.JUDGMENT && edges.none { it.to == n.id && (it.relation == Relation.SUPPORTS || it.relation == Relation.CAUSES) }) {
                out += Gap("没依据", n.id, "判断没有支撑", "「${n.claim}」——凭什么这么判断？找一条事实撑住它，或者推翻它。", 4)
            }
        }
        edges.filter { it.relation == Relation.OPPOSES || it.relation == Relation.COUNTER }.forEach { e ->
            val x = nodes.firstOrNull { it.id == e.from }; val y = nodes.firstOrNull { it.id == e.to }
            if (x != null && y != null) out += Gap("矛盾", e.to, "两条论断${e.relation.label}，还没处理", "「${x.claim}」和「${y.claim}」${e.relation.label}——在什么条件下各自成立？", 5)
        }
        if (nodes.size >= 4 && edges.none { it.relation == Relation.PREREQ }) {
            out += Gap("没有主干", nodes.first().id, "没有前置链，看不出先学什么后学什么", "这个主题里，哪一条是其他几条的前提？画出先后顺序。", 2)
        }
        return out.sortedByDescending { it.weight }.distinctBy { it.nodeId to it.kind }
    }

    /** 下一次去 GPT / Gemini 该聊的 1–3 个问题。 */
    fun nextQuestions(a: Atlas, topicId: String, n: Int = 3): List<String> = gaps(a, topicId).take(n).map { it.question }

    // ------------------------------------------------------------------ 归纳

    /** 体系总结：让模型归纳主干、层级、关键关系和缺口（结果作为一条“方法”或“判断”回挂进图）。 */
    fun synthesisPrompt(a: Atlas, topicId: String): String = buildString {
        val t = a.topics.firstOrNull { it.id == topicId }
        appendLine("【主题】${t?.name.orEmpty()}")
        appendLine(outline(a, topicId))
        appendLine()
        appendLine("把这个主题归纳成一个体系：一句话的主干，下面不超过 4 个层级分支（每个分支一句话，引用论断编号），")
        appendLine("指出最关键的一条因果链，以及最大的一个缺口。不要复述，要归纳。只输出：")
        appendLine("【主干】……")
        appendLine("【分支】……（每个分支一行）")
        appendLine("【关键链】A → B → C")
        append("【最大缺口】……")
    }

    fun parseSynthesis(reply: String): String? {
        val t = Tags.parse(reply)
        val trunk = t["主干"] ?: return null
        val branches = Regex("""【分支】([^\n]+)""").findAll(reply).map { "· " + it.groupValues[1].trim() }.toList()
        return buildString {
            appendLine("主干：$trunk")
            branches.forEach { appendLine(it) }
            t["关键链"]?.let { appendLine("关键链：$it") }
            t["最大缺口"]?.let { append("最大缺口：$it") }
        }.trim()
    }

    /** 大纲：给开场白、Notion、GPT 用。 */
    fun outline(a: Atlas, topicId: String, max: Int = 25): String {
        val nodes = a.nodes.filter { it.topicId == topicId }.takeLast(max)
        val ids = nodes.map { it.id }.toSet()
        return buildString {
            nodes.groupBy { it.kind }.forEach { (k, list) ->
                appendLine("${k.label}：")
                list.forEach { appendLine("  ${it.id}　${it.claim}（${it.confidence}${if (it.verified.isNotBlank()) "，已验证：${it.verified}" else ""}）") }
            }
            val es = a.edges.filter { it.from in ids && it.to in ids }
            if (es.isNotEmpty()) {
                appendLine("关系：")
                es.forEach { appendLine("  ${it.from} ${it.relation.label} ${it.to}${if (it.note.isNotBlank()) "（${it.note}）" else ""}") }
            }
        }.trim()
    }

    fun verify(a: Atlas, nodeId: String, result: String): Atlas =
        a.copy(nodes = a.nodes.map { if (it.id == nodeId) it.copy(verified = result.take(80), confidence = "高") else it })

    // ------------------------------------------------------------------ 工具

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    /** 粗略的“几乎一样”：去掉标点后，短的那句的 80% 以上的字在长的那句里按顺序出现。 */
    fun similar(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (s, l) = if (x.length <= y.length) x to y else y to x
        if (s.length < 0.6 * l.length) return false
        var i = 0
        var hit = 0
        for (ch in s) {
            val j = l.indexOf(ch, i)
            if (j >= 0) { hit++; i = j + 1 }
        }
        return hit >= 0.8 * s.length
    }
}
