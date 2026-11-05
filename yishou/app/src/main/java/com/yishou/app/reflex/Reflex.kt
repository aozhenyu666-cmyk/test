package com.yishou.app.reflex

import com.yishou.app.judge.Guard
import com.yishou.app.sharp.Sharp
import com.yishou.app.think.Words

/**
 * 触发器库：“下次看到【信号】，我先【动作】。”
 * 人和人想问题的差距，很大一块是脑子里“看到什么 → 先做什么”的条件反射不一样。
 * 每次判完填一句，入库后按间隔推回来默写；默写也是低能量放行的材料。
 */
data class Reflex(
    val id: Long = 0,
    val signal: String,
    val action: String,
    /** 从哪道题来的（锁题 id） */
    val source: String,
    val createdAt: Long,
    /** 下次该推回来的时间 */
    val dueAt: Long,
    /** 连着默写对了几次 */
    val reps: Int = 0,
)

object Reflexes {

    private const val DAY = 24 * 60 * 60_000L

    /** 默写对一次，间隔往后推一档；错了回到第一档 */
    val INTERVALS = listOf(1 * DAY, 3 * DAY, 7 * DAY, 14 * DAY, 30 * DAY)

    /**
     * 检查填的这一句。返回 null 表示合格，否则是要显示的原因。
     * 信号要能在题目里找到原文（不然下次认不出来）；动作要具体，不能是套话，也不能只是把信号再说一遍。
     */
    fun check(signal: String, action: String, question: String): String? {
        if (Words.substance(signal) < 2) return "信号写出题目里的几个字"
        if (!Words.quoted(question, signal)) return "信号要从题目里照抄，下次才认得出来"
        if (Words.substance(action) < 4) return "动作写具体：第一秒先做什么"
        if (Sharp.hollow(action) != null || Words.substance(stripGeneric(action)) < 4) return "“认真读题”这类话放在哪都对，写一个具体动作"
        if (Guard.novel(action, listOf(signal)) < 3) return "动作不能只是把信号再说一遍"
        return null
    }

    /** 放在哪道题上都成立的“动作” */
    private val GENERIC = listOf(
        "认真", "仔细", "读题", "审题", "看题", "想想", "多想", "思考", "分析", "注意", "冷静", "小心", "慢一点", "慢慢", "好好", "看清楚", "看清", "理解", "一下",
        "先", "再", "要", "把", "地",
    )

    private fun stripGeneric(s: String): String {
        var t = s
        GENERIC.sortedByDescending { it.length }.forEach { t = t.replace(it, "") }
        return t
    }

    fun create(signal: String, action: String, source: String, now: Long) =
        Reflex(signal = signal.trim(), action = action.trim(), source = source, createdAt = now, dueAt = now + INTERVALS[0])

    /** 默写算不算对：按顺序对上原句六成以上的字，且至少 4 个实在字 */
    fun recalled(expected: String, typed: String): Boolean {
        val e = Words.norm(expected)
        val t = Words.norm(typed)
        if (Words.substance(typed) < 4 || e.isEmpty()) return false
        return Words.inOrder(e, t) >= e.length * 0.6
    }

    fun afterRecall(r: Reflex, ok: Boolean, now: Long): Reflex {
        val reps = if (ok) r.reps + 1 else 0
        val gap = INTERVALS[(reps).coerceAtMost(INTERVALS.lastIndex)]
        return r.copy(reps = reps, dueAt = now + gap)
    }

    /** 挑一条来默写：先挑到期最久的；都没到期就挑最早到期的（低能量路不能因为“都没到期”就断） */
    fun pick(all: List<Reflex>, now: Long, exclude: Set<Long> = emptySet()): Reflex? =
        all.filter { it.id !in exclude }.minByOrNull { it.dueAt - now }
}
