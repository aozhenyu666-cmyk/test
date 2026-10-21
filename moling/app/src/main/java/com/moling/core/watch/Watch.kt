package com.moling.core.watch

import com.moling.core.link.ZkStatus
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Conclude
import com.moling.core.power.Tier
import com.moling.core.predict.Metric
import kotlinx.serialization.Serializable
import java.time.Instant

/*
 * 监督（v4 C4）：偏离时一步一步升级、说法对证据、她给自己定的唤醒。
 */

/** 升级阶梯的一级：通知一句 → 小窗展开 → 提议开锁 → 按授权书开锁（总控 60 秒倒计时）。 */
@Serializable
enum class Rung(val label: String) { NOTICE("提醒一句"), WINDOW("小窗"), PROPOSE("提议开锁"), AUTO("按授权书开锁") }

/**
 * 一次偏离的阶梯。[key] = “日期:水道 id”。
 * [idleSince]：偏离暂停（回去做事了）的时刻；15 分钟内又偏离，接着这一级走，不从头提醒。
 */
@Serializable
data class Ladder(
    val key: String,
    val rung: Rung,
    val at: Long,
    val answered: Boolean = false,
    val idleSince: Long = 0,
    val proposalId: String = "",
)

object Ladders {
    const val GAP_MIN = 5
    const val RESUME_MIN = 15

    /** 各等级最高走到哪一级：参谋只到小窗，规划者到提议，管家到按授权书开锁。 */
    fun top(tier: Tier): Rung = when (tier) {
        Tier.ADVISOR -> Rung.WINDOW
        Tier.PLANNER -> Rung.PROPOSE
        Tier.STEWARD -> Rung.AUTO
    }

    /**
     * 这一分钟：[driftKey] 是此刻偏离的那条水道（null = 没偏离）。返回新的阶梯和这一分钟要上的那一级（null = 不出声）。
     * 他回应过（[Ladder.answered]）这一次偏离就不再升级。
     */
    fun step(cur: Ladder?, driftKey: String?, now: Long, tier: Tier, gapMin: Int = GAP_MIN): Pair<Ladder?, Rung?> {
        if (driftKey == null) {
            if (cur == null) return null to null
            return cur.copy(idleSince = cur.idleSince.takeIf { it > 0 } ?: now) to null
        }
        val resumable = cur != null && cur.key == driftKey &&
            (cur.idleSince == 0L || now - cur.idleSince < RESUME_MIN * 60_000L)
        if (!resumable) return Ladder(driftKey, Rung.NOTICE, now) to Rung.NOTICE
        val c = cur!!.copy(idleSince = 0)
        if (c.answered || now - c.at < gapMin * 60_000L) return c to null
        val next = Rung.entries.getOrNull(c.rung.ordinal + 1) ?: return c to null
        if (next.ordinal > top(tier).ordinal) return c to null
        return c.copy(rung = next, at = now) to next
    }
}

/** 他（或演员）的一句可以对账的说法。 */
@Serializable
data class Claim(
    val at: Long,
    val who: String,
    val text: String,
    val metric: Metric,
    val minutes: Int,
    /** 对得上 / 总控记的是 X 分钟 / 看不到。 */
    val verdict: String = "",
)

object Claims {
    private val work = Regex("""(做|写|学|看书|背|练|干|弄|搞)[^，。！？\d]{0,6}?了?\s*(快|差不多|将近)?\s*(\d+(?:\.\d+)?)\s*(个)?\s*(分钟|小时)""")
    private val play = Regex("""(刷|玩|看视频|打游戏|追剧|看剧)[^，。！？\d]{0,6}?了?\s*(快|差不多|将近)?\s*(\d+(?:\.\d+)?)\s*(个)?\s*(分钟|小时)""")

    /** 认出“做了 40 分钟”“刷了半小时”这类说法；中文数字和“半小时”先换掉。认不出返回 null。 */
    fun parse(text: String, who: String, now: Long): Claim? {
        val t = Conclude.cnDurations(text.replace("一个半小时", "90分钟").replace("半个小时", "30分钟").replace("半小时", "30分钟"))
        fun minutes(m: MatchResult): Int {
            val n = m.groupValues[3].toDouble()
            return (if (m.groupValues[5] == "小时") n * 60 else n).toInt()
        }
        play.find(t)?.let { return Claim(now, who, text.take(80), Metric.PLAY, minutes(it)) }
        work.find(t)?.let { return Claim(now, who, text.take(80), Metric.WORK, minutes(it)) }
        return null
    }

    /**
     * 和总控的数对一下（总控只有“今天一共”，所以只能查“不可能”的情况）：
     * 说做了 N 分钟，但今天一共才记了明显更少；说只玩了 N 分钟，但今天已经记了明显更多。
     */
    fun check(c: Claim, s: ZkStatus?): String {
        if (s == null) return "看不到"
        fun far(a: Int, b: Int) = a - b > maxOf(10, b / 5)
        return when (c.metric) {
            Metric.WORK -> if (far(c.minutes, s.workMinutesToday)) "总控记的今天做事一共 ${s.workMinutesToday} 分钟" else "对得上"
            Metric.PLAY -> if (far(s.playMinutesToday, c.minutes)) "总控记的今天娱乐一共 ${s.playMinutesToday} 分钟" else "对得上"
            else -> "看不到"
        }
    }
}

/** 她给自己定的唤醒：到点她会被叫醒，带着这句话来找他（像人自己记得一样）。 */
@Serializable
data class Wake(val id: String, val at: Long, val note: String, val daily: Boolean = false, val setAt: Long = 0)

object Wakes {
    /** 到点的，以及到点后要留下的（每天的挪到明天）。错过的（App 没在跑）照样送到，带上原定时间。 */
    fun due(list: List<Wake>, now: Long): Pair<List<Wake>, List<Wake>> {
        val due = list.filter { it.at <= now }
        val keep = list.filter { it.at > now } + due.filter { it.daily }.map { w ->
            var at = w.at
            while (at <= now) at += 24 * 3_600_000L
            w.copy(at = at)
        }
        return due to keep.sortedBy { it.at }
    }

    fun text(w: Wake, now: Long): String {
        val t = Instant.ofEpochMilli(w.at).atZone(BEIJING)
        val late = now - w.at > 2 * 60_000L
        return w.note + if (late) "（原定 %02d:%02d）".format(t.hour, t.minute) else ""
    }
}
