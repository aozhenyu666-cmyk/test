package com.moling.core.review

import kotlinx.serialization.Serializable

/*
 * 她摸清的他的规律（v12，“了解”的第三层）：不是笼统的描述，而是“当……，就……”。
 * 夜里她拿白天的数字（哪种出现方式管用、几点最容易刷、三手棋下得怎样、几点脑子转得快）提 1–2 条，
 * **他点“对”才生效**；生效的规律写进她的提示词，她按规律来找他。
 */

@Serializable
data class Rule(
    val id: String,
    val at: Long,
    /** 当……（情境）。 */
    val whenText: String,
    /** 就……（她该怎么做 / 他会怎样）。 */
    val thenText: String,
    /** 依据（数字）。 */
    val why: String = "",
    /** proposed / kept / dropped。 */
    val status: String = "proposed",
)

object Rules {
    const val KEEP = 30

    fun line(r: Rule) = "当${r.whenText}，${r.thenText}"

    fun kept(rules: List<Rule>) = rules.filter { it.status == "kept" }

    fun prompt(stats: String, existing: List<Rule>, about: String) = buildString {
        appendLine("【你这几天看到的数字】\n$stats")
        if (existing.isNotEmpty()) appendLine("【已经有的规律】\n" + existing.joinToString("\n") { "- " + line(it) + "（${it.status}）" })
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(600)}")
        appendLine()
        appendLine("从数字里找 1–2 条“当……，就……”的规律：什么情境下他会卡 / 会刷 / 状态好，什么方式最能把他拉回来。")
        appendLine("必须有数字依据；不重复已有的；拿不准就不写。只输出：")
        append("【规律】当……｜就……｜依据（数字）")
    }

    fun parse(reply: String, now: Long, start: Int): List<Rule> =
        Regex("""【规律】([^\n]+)""").findAll(reply).mapIndexedNotNull { i, m ->
            val f = m.groupValues[1].split('｜', '|').map { it.trim() }
            val w = f.getOrNull(0).orEmpty().removePrefix("当").take(40)
            val t = f.getOrNull(1).orEmpty().removePrefix("就").take(50)
            if (w.length < 2 || t.length < 2) null else Rule("R${start + i}", now, w, t, f.getOrNull(2).orEmpty().take(60))
        }.take(2).toList()
}
