package com.yishou.app.act

import com.yishou.app.sharp.Sharp
import com.yishou.app.think.Words

/**
 * 下一步动作（执行意图）：“到了【什么时候/什么情况】，我就【做一件具体的事】，做【几分钟】。”
 *
 * 为什么是它：长期目标清楚、日常启动不了，缺的往往不是动力，是“此刻具体干什么”。
 * “今天复习行测”启动不了；“吃完饭坐到桌前，打开粉笔做言语第 3 套前 5 题，15 分钟”能启动。
 * 写成“情境 → 动作”的句子，到了那个情境更容易自动接上（执行意图的研究里效果比较稳），
 * 也给了一手一个能检查的东西：到点做了没有。
 *
 * 这里只管两件事：写出来的够不够具体；事后做没做（不验收内容，只问做没做）。
 */
data class NextAction(
    /** 什么时候 / 什么情况开始 */
    val cue: String,
    /** 具体做什么 */
    val action: String,
    /** 做几分钟 */
    val minutes: Int,
    /** 定在几点（没定具体时间就是 null，由情境触发） */
    val at: Long? = null,
)

enum class Outcome { DONE, PARTIAL, MISSED }

data class ActionRecord(val action: NextAction, val createdAt: Long, val outcome: Outcome? = null, val checkedAt: Long? = null)

object NextActions {

    /** 太大、太空的动作：写了等于没写 */
    private val VAGUE = listOf(
        "学习", "复习", "看书", "做题", "刷题", "准备", "努力", "搞一下", "弄一下", "研究一下", "看看", "整理一下", "规划",
        "找工作", "投简历", "备考", "提高", "加油",
    )

    /** 动作里像“具体对象”的东西：数字、第几、页、题、套、篇、份、封、个 */
    private val CONCRETE = Regex("\\d|[一二三四五六七八九十两]+\\s*[道题页套篇份封个节章条]|第\\s*[\\d一二三四五六七八九十]+")

    /** 情境里像“时间或事件”的东西 */
    private val CUE = Regex("\\d{1,2}\\s*[:：点]|早上|上午|中午|下午|晚上|饭后|吃完|起床|到家|下课|下班|洗完|醒来|睡前|坐到|打开|放下|一.{0,4}就|之后|以后|的时候")

    /** 一到也不知道是哪一刻的“情境” */
    private val VAGUE_CUE = Regex("有空|有时间|闲下来|方便的时候|想起来|看情况|心情好|状态好|准备好")

    const val MAX_MINUTES = 30
    const val MIN_MINUTES = 2

    private val PATTERN = Regex("(?:当|到了|等)?(.+?)[，,]\\s*(?:我)?就(.+?)(?:[，,（(]\\s*(\\d+)\\s*分钟?[)）]?)?[。.]?$")

    /** 读一句“……，我就……（N 分钟）”。读不出两半就返回 null */
    fun parse(text: String, defaultMinutes: Int = 15): NextAction? {
        val m = PATTERN.find(text.trim()) ?: return null
        val cue = m.groupValues[1].trim()
        val act = m.groupValues[2].trim().trimEnd('。', '.')
        val min = m.groupValues[3].toIntOrNull() ?: Words.numbers(act).lastOrNull()?.takeIf { act.contains("分钟") }?.toInt() ?: defaultMinutes
        if (cue.isEmpty() || act.isEmpty()) return null
        return NextAction(cue, act.replace(Regex("[，,]?\\s*\\d+\\s*分钟$"), "").trim(), min)
    }

    /** 够不够具体。返回 null 表示可以，否则是要显示的一句话 */
    fun check(a: NextAction): String? {
        if (a.at == null && (VAGUE_CUE.containsMatchIn(a.cue) || !CUE.containsMatchIn(a.cue))) return "写清楚什么时候开始：几点，或者“吃完饭”“坐到桌前”这种一到就知道的时刻"
        if (Words.substance(a.action) < 6) return "动作再具体一点：做什么、做哪一份"
        if (Sharp.hollow(a.action) != null) return "这是一句空话，换成一个能看见的动作"
        var left = a.action
        VAGUE.sortedByDescending { it.length }.forEach { left = left.replace(it, "") }
        if (!CONCRETE.containsMatchIn(a.action) && Words.substance(left) < 4) return "太大了：“${a.action}”做不完也说不清做没做。写到第几套、前几题、哪一页"
        if (a.minutes !in MIN_MINUTES..MAX_MINUTES) return "一次只定 $MIN_MINUTES–$MAX_MINUTES 分钟，做完再定下一步"
        return null
    }

    /** 一段时间里定下的动作，做到了几成（部分完成算半个） */
    fun completion(records: List<ActionRecord>, from: Long, to: Long): Double? {
        val checked = records.filter { it.createdAt in from until to && it.outcome != null }
        if (checked.isEmpty()) return null
        val score = checked.sumOf { when (it.outcome) { Outcome.DONE -> 1.0; Outcome.PARTIAL -> 0.5; else -> 0.0 } }
        return score / checked.size
    }

    /** 到点了还没回报的：该问一句“做了吗” */
    fun due(records: List<ActionRecord>, now: Long): List<ActionRecord> = records.filter { r ->
        r.outcome == null && (r.action.at ?: r.createdAt) + r.action.minutes * 60_000L <= now
    }
}
