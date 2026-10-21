package com.moling.core.spar

import com.moling.core.atlas.Atlas
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.atlas.Relation
import com.moling.core.mind.Tags
import com.moling.core.live.Live
import com.moling.core.live.Quizzes
import kotlinx.serialization.Serializable

/*
 * 平时也让脑子转起来（v12）：“一手”的精神，墨灵自己的做法——不碰“一手” App。
 *
 *  - 思维转速（[Rpm]）：最近 3 小时他做过几次“动脑的动作”（点选接招、捉虫、下一手棋、核实论断、
 *    判断她的推断对错、拍板提议、认真说一段话）。转速低、又闲着，她就来下一局。
 *  - 三手棋（[Chess]）：从他的体系图里挑一条（优先缺口），她故意给一个带漏洞的版本——
 *    第一手：点出漏洞；第二手：按他的修正，下一步最可能怎样；第三手：她打 0–3 分，
 *    把这局推出来的新结论挂进体系图（和原来那条连上线）。全程点选，不用打字。
 */

/** 动脑的动作。[weight] 越大越“费脑”。 */
@Serializable
enum class MindAct(val label: String, val weight: Int) {
    TAP("接了她的招", 1),
    QUIZ("捉虫", 2),
    CHESS("下了一手棋", 2),
    VERIFY("核实了论断", 2),
    JUDGE("判断了她的推断", 1),
    DECIDE("拍板了一条提议", 1),
    TRAIN("一手式追问", 2),
    SAY("认真说了一段", 1),
}

@Serializable
data class MindMark(val at: Long, val act: MindAct)

object Rpm {
    const val WINDOW_MS = 3 * 3_600_000L
    const val LOW = 3
    const val KEEP = 300

    fun score(marks: List<MindMark>, now: Long, windowMs: Long = WINDOW_MS): Int =
        marks.filter { it.at in (now - windowMs)..now }.sumOf { it.act.weight }

    fun low(marks: List<MindMark>, now: Long): Boolean = score(marks, now) < LOW

    /** 给人看：“最近 3 小时动脑 7 次（捉虫 2、下棋 3、接招 2）”。 */
    fun line(marks: List<MindMark>, now: Long): String {
        val r = marks.filter { it.at in (now - WINDOW_MS)..now }
        if (r.isEmpty()) return "最近 3 小时还没动过脑"
        return "最近 3 小时动脑 ${score(marks, now)} 分（" + r.groupBy { it.act }.entries.joinToString("、") { "${it.key.label} ${it.value.size}" } + "）"
    }

    /** 今天每小时的转速（24 个数），画图用。 */
    fun byHour(marks: List<MindMark>, dayStart: Long): List<Int> {
        val out = IntArray(24)
        marks.filter { it.at >= dayStart && it.at < dayStart + 86_400_000L }.forEach { out[((it.at - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)] += it.act.weight }
        return out.toList()
    }
}

/** 一局三手棋。[turn] = 他接下来要下第几手（1..3）；4 = 下完了。 */
@Serializable
data class Chess(
    val id: String,
    val startedAt: Long,
    val topicId: String,
    val nodeId: String,
    /** 原来那条论断。 */
    val source: String,
    /** 她故意给的带漏洞的版本。 */
    val bait: String,
    /** 漏洞在哪（第一手的标准答案）。 */
    val flaw: String,
    /** 第一手的选项（打乱过；含正确的那个）。 */
    val options: List<String>,
    /** 第一手的正确选项（原文）。 */
    val answer: String = "",
    val turn: Int = 1,
    val moves: List<String> = emptyList(),
    val score: Int = -1,
    val conclusion: String = "",
)

/** 下完一手她怎么回：[line] 她的话；[choices] 下一手的选项（空 = 这局结束）；[score] 结束时的分。 */
data class ChessTurn(val line: String, val choices: List<String>, val done: Boolean, val score: Int = -1, val conclusion: String = "", val nodeAdded: String = "")

object Chesses {
    const val FREE = "我自己说一句"

    /** 挑一条来下：优先有缺口的（待验证 / 没依据 / 矛盾），再挑判断类；最近下过的不挑。 */
    fun pick(a: Atlas, recent: Collection<String>, now: Long): Node? {
        val gapIds = a.topics.flatMap { t -> AtlasOps.gaps(a, t.id).sortedByDescending { it.weight }.map { it.nodeId } }
        val byGap = gapIds.mapNotNull { id -> a.nodes.firstOrNull { it.id == id } }
        val judged = a.nodes.filter { it.kind == NodeKind.JUDGMENT || it.kind == NodeKind.HYPOTHESIS || it.kind == NodeKind.METHOD }
        val pool = (byGap + judged).distinctBy { it.id }.filter { it.id !in recent && it.claim.length in 6..80 }
        if (pool.isEmpty()) return null
        return pool[((now / 60_000L) % minOf(pool.size, 5)).toInt()]
    }

    fun openPrompt(node: Node, outline: String) = buildString {
        appendLine("【他体系图里的一条】${node.claim}（${node.kind.label}，把握${node.confidence}）")
        if (outline.isNotBlank()) appendLine("【这个主题的主干】\n${outline.take(800)}")
        appendLine()
        appendLine("你要和他下一局“三手棋”。第一手：把这条改成一个**听起来很像真的、但藏了一个逻辑漏洞**的说法（偷换前提、以偏概全、因果倒置、漏掉条件之类，只藏一个）。")
        appendLine("再给 3 个选项让他点出漏洞：一个是正确的漏洞，两个是看似合理的干扰。只输出：")
        appendLine("【暴论】带漏洞的说法（一句）")
        appendLine("【漏洞】漏洞在哪（一句，正确答案）")
        append("【选项】正确的漏洞（≤12 字）｜干扰一（≤12 字）｜干扰二（≤12 字）")
    }

    fun parseOpen(reply: String, node: Node, now: Long, shuffle: Int): Chess? {
        val t = Tags.parse(reply)
        val bait = t["暴论"]?.trim()?.take(100).orEmpty()
        val flaw = t["漏洞"]?.trim()?.take(80).orEmpty()
        val opts = t["选项"]?.split('｜', '|')?.map { it.trim().take(14) }?.filter { it.isNotBlank() }.orEmpty()
        if (bait.length < 6 || flaw.isBlank() || opts.size < 2) return null
        return Chess("c-$now", now, node.topicId, node.id, node.claim, bait, flaw, rotate(opts.take(3), shuffle), answer = opts[0])
    }

    /** 模型不可用：用捉虫题的办法翻一个数字 / 否定词。 */
    fun local(node: Node, now: Long, shuffle: Int): Chess? {
        val q = Quizzes.local(node.claim) ?: return null
        val wrongPart = q.parts[q.wrong]
        val opts = listOf("「${wrongPart.take(8)}」这里不对", "前提没毛病", "结论太绝对")
        return Chess("c-$now", now, node.topicId, node.id, node.claim, q.parts.joinToString(""), "原本是「${q.fix}」", rotate(opts, shuffle), answer = opts[0])
    }

    /** 选项转一转（正确答案不总在第一个），最后加“我自己说一句”。 */
    private fun rotate(l: List<String>, k: Int): List<String> = if (l.isEmpty()) l else l.indices.map { l[(it + k) % l.size] } + FREE

    /** 第一手点没点中。 */
    fun firstRight(c: Chess, picked: String): Boolean = picked == c.answer

    fun movePrompt(c: Chess, picked: String) = buildString {
        appendLine("你在和他下“三手棋”。")
        appendLine("【原论断】${c.source}")
        appendLine("【你给的带漏洞版本】${c.bait}")
        appendLine("【正确的漏洞】${c.flaw}")
        c.moves.forEachIndexed { i, m -> appendLine("【他第 ${i + 1} 手】$m") }
        appendLine("【他这一手】$picked")
        appendLine()
        when (c.turn) {
            1 -> {
                appendLine("先一句话判他这一手点没点中（点中就夸一句，没点中就直接说漏洞在哪，不说教）。")
                appendLine("然后第二手：如果按修正后的说法去做，下一步最可能发生什么连锁反应？给 2–3 个选项。只输出：")
                appendLine("【说】你的话（两句以内）")
                append("【选】选项一｜选项二｜选项三")
            }
            2 -> {
                appendLine("这是最后一手：给这局打分 0–3（第一手点没点中、第二手推得合不合理），一句点评；再从这局里提炼一条能挂进他体系图的新论断。只输出：")
                appendLine("【说】你的点评（一句）")
                appendLine("【分】0–3")
                append("【结论】新论断（一句，能单独看懂）｜类型（事实/判断/假设/方法）｜和原论断的关系（支撑/反例/前置/导致）｜把握（高/中/低）")
            }
            else -> append("【说】这局下完了。")
        }
    }

    data class Move(val line: String, val choices: List<String>, val score: Int, val claim: String, val kind: NodeKind?, val relation: Relation?, val confidence: String)

    fun parseMove(reply: String): Move {
        val t = Tags.parse(reply)
        val f = t["结论"]?.split('｜', '|')?.map { it.trim() }.orEmpty()
        return Move(
            line = t["说"]?.trim()?.take(160).orEmpty(),
            choices = Live.choices(reply),
            score = t["分"]?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 3) ?: -1,
            claim = f.getOrNull(0).orEmpty().take(80),
            kind = NodeKind.entries.firstOrNull { it.label == f.getOrNull(1) },
            relation = Relation.entries.firstOrNull { it.label == f.getOrNull(2) },
            confidence = f.getOrNull(3)?.takeIf { it in setOf("高", "中", "低") } ?: "中",
        )
    }
}
