package com.moling.core.forage

import com.moling.core.atlas.Gap
import kotlinx.serialization.Serializable

/*
 * 主动采蜜（v12）：他体系图里挂着没解决的问题 / 没验证的假设 / 没依据的判断，
 * 她闲下来时挑一个，用深想模型把正反两面整理成几条简报，等他看——
 * 他点“收下”才进体系图（标“采蜜，待核”、把握低）；同时把这个问题挂上黑板，让 GPT 也来答。
 * 不联网搜（模型只给它知道的），所以每条都要标“常识 / 研究 / 经验 / 猜测”，不许编具体数字和出处。
 */

@Serializable
data class Brief(
    val id: String,
    val at: Long,
    val nodeId: String,
    val topicId: String,
    val question: String,
    /** 每条：“支持：……（常识）” / “反对：……（猜测）”。 */
    val lines: List<String>,
    /** new / kept / dropped。 */
    val status: String = "new",
)

object Forage {
    const val KEEP = 30

    /** 挑一个缺口：没回答 / 待验证 / 没依据 / 矛盾，最近 3 天采过的不挑。 */
    fun pick(gaps: List<Gap>, recent: Collection<String>): Gap? =
        gaps.filter { it.kind in setOf("没回答", "待验证", "没依据", "矛盾") && it.nodeId !in recent }.maxByOrNull { it.weight }

    fun prompt(question: String, outline: String, about: String) = buildString {
        appendLine("【他体系图里没解决的】$question")
        if (outline.isNotBlank()) appendLine("【这个主题的主干】\n${outline.take(800)}")
        if (about.isNotBlank()) appendLine("【你对他的了解】\n${about.take(500)}")
        appendLine()
        appendLine("替他把这个问题的正反两面整理成 3–5 条简报，让他只需要判断、不用从零找。规矩：")
        appendLine("- 只写你确实知道的；每条最后用括号标来源类型：常识 / 研究 / 经验 / 猜测；")
        appendLine("- 不许编具体数字、人名、书名、链接；拿不准就标“猜测”；")
        appendLine("- 至少一条支持、一条反对；最后一条写“最便宜的验证办法”。只输出：")
        append("【条】支持：……（常识）\n【条】反对：……（猜测）\n【条】验证：……")
    }

    fun parse(reply: String): List<String> =
        Regex("""【条】([^\n]+)""").findAll(reply).map { it.groupValues[1].trim().take(120) }.filter { it.length >= 6 }.take(5).toList()

    /** 收下时变成体系图材料的文字。 */
    fun asMaterial(b: Brief): String = "关于「${b.question}」的简报（墨灵采蜜，待核）：\n" + b.lines.joinToString("\n")
}
