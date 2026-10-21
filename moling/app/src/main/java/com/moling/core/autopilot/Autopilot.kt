package com.moling.core.autopilot

import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.usage.Session

/*
 * 自动驾驶识别（v9）：他不是在专注，而是脑子掉线、靠本能在刷 / 在横跳。
 *
 * 只用系统“使用情况访问”已经给的“哪个应用、几点到几点”，不看屏幕内容、不加权限。四个信号：
 *  - 横跳：5 分钟里在 3 个以上应用之间切了 6 次以上（多巴胺寻觅）；
 *  - 回刷：15 分钟里反复回到同一个娱乐应用（退出去又马上回来）；
 *  - 空拿起：20 分钟里拿起手机 4 次以上、每次都很短（拿起来不知道要干什么）；
 *  - 连刷：同一个娱乐应用连着刷很久。
 * 该做事的时候（今天定稿的主河道 / 支流 / 源头里）分数乘 1.4。60 分以上算“在自动驾驶”。
 */

data class AutopilotSignal(val score: Int, val reasons: List<String>) {
    val on: Boolean get() = score >= Autopilot.ON
}

object Autopilot {
    const val ON = 60
    private const val MIN = 60_000L

    private fun isPlay(c: String) = c == "ENTERTAINMENT" || c == "SHOPPING"

    /** 同一个应用中间断开不到 [gapMs] 的几段合成一段。 */
    private fun merge(s: List<Session>, gapMs: Long): List<Session> {
        val out = mutableListOf<Session>()
        s.sortedBy { it.start }.forEach { x ->
            val last = out.lastOrNull()
            if (last != null && last.pkg == x.pkg && x.start - last.end <= gapMs) out[out.lastIndex] = last.copy(end = maxOf(last.end, x.end))
            else out += x
        }
        return out
    }

    fun read(sessions: List<Session>, now: Long, plan: FlowPlan? = null): AutopilotSignal {
        val recent = merge(sessions.filter { it.end > now - 30 * MIN && it.start <= now }, 10_000L)
        if (recent.isEmpty()) return AutopilotSignal(0, emptyList())
        var score = 0.0
        val why = mutableListOf<String>()

        // 横跳
        val last5 = recent.filter { it.end > now - 5 * MIN }
        val switches = (last5.size - 1).coerceAtLeast(0)
        val distinct = last5.map { it.pkg }.distinct().size
        if (switches >= 6 && distinct >= 3) {
            score += 35
            why += "5 分钟里在 $distinct 个应用之间切了 $switches 次"
        }

        // 回刷：15 分钟里同一个娱乐应用开了 4 次以上（中间离开过）
        val play15 = recent.filter { isPlay(it.category) && it.end > now - 15 * MIN }
        play15.groupBy { it.pkg }.maxByOrNull { it.value.size }?.let { (_, v) ->
            if (v.size >= 4) {
                score += 30
                why += "15 分钟里回到「${v.first().label}」${v.size} 次"
            }
        }

        // 空拿起：20 分钟里，和上一段隔开 1 分钟以上才开始的“拿起”，4 次以上、多数不到 3 分钟
        val last20 = recent.filter { it.start > now - 20 * MIN }
        var lastEnd = 0L
        val pickups = mutableListOf<Long>()
        var cur = 0L
        last20.forEach { x ->
            if (x.start - lastEnd > MIN) { if (cur > 0) pickups += cur; cur = 0 }
            cur += x.end - x.start
            lastEnd = maxOf(lastEnd, x.end)
        }
        if (cur > 0) pickups += cur
        if (pickups.size >= 4 && pickups.count { it < 3 * MIN } >= pickups.size * 2 / 3) {
            score += 25
            why += "20 分钟里拿起手机 ${pickups.size} 次，大多不到 3 分钟"
        }

        // 连刷：最后一段是娱乐、连着很久
        val tail = recent.last()
        if (isPlay(tail.category) && tail.end >= now - MIN) {
            val m = ((tail.end - tail.start) / MIN).toInt()
            when {
                m >= 45 -> { score += 35; why += "「${tail.label}」连着 $m 分钟" }
                m >= 25 -> { score += 20; why += "「${tail.label}」连着 $m 分钟" }
            }
        }

        // 该做事的时候
        val minute = Flow.minuteOf(now)
        val working = plan?.takeIf { it.confirmed }?.let { Flow.current(it, minute) }
            ?.takeIf { minute in it.startMin until it.endMin && it.kind in setOf(ChannelKind.MAIN, ChannelKind.SIDE, ChannelKind.SPRING) }
        if (working != null && score > 0) {
            score *= 1.4
            why += "现在本该是「${working.title}」"
        }
        return AutopilotSignal(score.toInt().coerceIn(0, 100), why)
    }
}
