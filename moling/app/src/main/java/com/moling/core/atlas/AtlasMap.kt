package com.moling.core.atlas

/*
 * 体系图的“沙盘”（v9）：每个主题是一座岛；核实过 / 把握高 / 他确认过的论断是亮的，其余在雾里；
 * 缺口（待验证、没依据、矛盾……）是黑洞。界面按这个画，一眼看出哪里亮了、哪里还黑着。
 */

data class Dot(val id: String, val claim: String, val kind: NodeKind, val lit: Boolean)

data class Island(
    val topicId: String,
    val name: String,
    val dots: List<Dot>,
    val gaps: List<Gap>,
) {
    val lit: Int get() = dots.count { it.lit }
    /** 亮了几成（0..1）。 */
    val progress: Float get() = if (dots.isEmpty()) 0f else lit / dots.size.toFloat()
}

object AtlasMap {
    fun lit(n: Node): Boolean = n.verified.isNotBlank() || n.confirmed || n.confidence == "高"

    fun islands(a: Atlas): List<Island> = a.topics.map { t ->
        val nodes = a.nodes.filter { it.topicId == t.id }
        Island(t.id, t.name, nodes.map { Dot(it.id, it.claim, it.kind, lit(it)) }, AtlasOps.gaps(a, t.id))
    }.sortedByDescending { it.dots.size }

    /** 一句话：“「元认知」亮了 6/10，还有 2 个黑洞”。 */
    fun line(i: Island): String = "「${i.name}」亮了 ${i.lit}/${i.dots.size}" + if (i.gaps.isNotEmpty()) "，还有 ${i.gaps.size} 个黑洞" else ""
}
