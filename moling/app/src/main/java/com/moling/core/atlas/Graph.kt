package com.moling.core.atlas

import com.moling.core.think.Recall

/*
 * 体系图的拓扑检索（v12）：不只按字面找，找到一条就顺着线（导致 / 支撑 / 反例 / 前置……）
 * 把它的上下游一起拎出来，再带上这个主题当前最大的缺口。对话和她思考时用。
 */
object Graph {
    /** 一条论断 + 它一跳之内的邻居，写成“A —导致→ B”。 */
    fun around(a: Atlas, id: String): List<String> {
        val n = a.nodes.associateBy { it.id }
        return a.edges.filter { it.from == id || it.to == id }.mapNotNull { e ->
            val x = n[e.from] ?: return@mapNotNull null
            val y = n[e.to] ?: return@mapNotNull null
            "${x.claim.take(40)} —${e.relation.label}→ ${y.claim.take(40)}"
        }
    }

    /**
     * 和 [query] 相关的知识：先按字面找最像的几条论断（种子），再顺着线拉出邻居，
     * 最后带上种子所在主题里分数最高的缺口。返回给提示词的几行字（最多 [k] 行）。
     */
    fun recall(query: String, a: Atlas, k: Int = 8): List<String> {
        val topics = a.topics.associate { it.id to it.name }
        val lines = a.nodes.map { it.id to "「${topics[it.topicId].orEmpty()}」${it.kind.label}：${it.claim}（把握${it.confidence}${if (it.verified.isNotBlank()) "，已核实" else ""}）" }
        val hit = Recall.relevant(query, lines.map { it.second }, 3).toSet()
        val seeds = lines.filter { it.second in hit }.map { it.first }
        if (seeds.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        seeds.forEach { id ->
            out += lines.first { it.first == id }.second
            around(a, id).take(3).forEach { out += "  ↳ $it" }
        }
        seeds.mapNotNull { id -> a.nodes.firstOrNull { it.id == id }?.topicId }.distinct().forEach { t ->
            AtlasOps.gaps(a, t).maxByOrNull { it.weight }?.let { out += "  ⚠ 缺口：${it.question.take(60)}" }
        }
        return out.distinct().take(k)
    }

    /** 加一条线（她推出来的连线）：两头都在、还没有同样的线才加。 */
    fun link(a: Atlas, from: String, to: String, r: Relation, note: String): Atlas {
        if (from == to || a.nodes.none { it.id == from } || a.nodes.none { it.id == to }) return a
        if (a.edges.any { it.from == from && it.to == to }) return a
        return a.copy(edges = a.edges + Edge(from, to, r, note.take(60)))
    }
}
