package com.yishou.app.act

/**
 * 周报只看软件外面也成立的东西。
 *
 * 一手里能刷的分（连胜、放行次数、判卷命中率）都可能只是“会过关”，不等于想得更清楚、做得更多。
 * 所以每周只看四个数，每个都对得上真实生活：
 *  1. 专注分钟：真坐下来做事的时间（专注流记录）；
 *  2. 说到做到：定下的下一步动作做到了几成；
 *  3. 题目：按题型的正确率和中位用时（题库、粉笔导入都算），看“终审”做了的那些是不是更准；
 *  4. 抢答：没写证据就落笔的次数。
 * 每月再加一次“裸考”：不开一手、限时做一套，和上月比——这才是迁移有没有发生的证据。
 */
data class Attempt(val type: String, val correct: Boolean, val ms: Long, val at: Long, val reviewed: Boolean, val rushed: Boolean)

data class FocusLog(val startedAt: Long, val minutes: Int)

data class TypeStat(val type: String, val n: Int, val accuracy: Double, val medianMs: Long, val reviewedAccuracy: Double?, val unreviewedAccuracy: Double?)

data class Week(
    val focusMinutes: Int,
    val actionCompletion: Double?,
    val actionsChecked: Int,
    val types: List<TypeStat>,
    val rushed: Int,
    val attempts: Int,
)

object Weekly {
    const val WEEK_MS = 7 * 24 * 60 * 60_000L

    fun summarize(from: Long, to: Long, attempts: List<Attempt>, actions: List<ActionRecord>, focus: List<FocusLog>): Week {
        val a = attempts.filter { it.at in from until to }
        val types = a.groupBy { it.type }.map { (t, xs) ->
            val sorted = xs.map { it.ms }.sorted()
            fun acc(ys: List<Attempt>) = if (ys.isEmpty()) null else ys.count { it.correct }.toDouble() / ys.size
            TypeStat(t, xs.size, acc(xs)!!, sorted[sorted.size / 2], acc(xs.filter { it.reviewed }), acc(xs.filter { !it.reviewed }))
        }.sortedByDescending { it.n }
        val checked = actions.count { it.createdAt in from until to && it.outcome != null }
        return Week(
            focusMinutes = focus.filter { it.startedAt in from until to }.sumOf { it.minutes },
            actionCompletion = NextActions.completion(actions, from, to),
            actionsChecked = checked,
            types = types,
            rushed = a.count { it.rushed },
            attempts = a.size,
        )
    }

    /** 和上周比，挑出变化最大的几句（只说事实，不评价人） */
    fun compare(prev: Week, cur: Week): List<String> {
        val out = mutableListOf<String>()
        val df = cur.focusMinutes - prev.focusMinutes
        out += "专注 ${cur.focusMinutes} 分钟（${sign(df)} 分钟）"
        cur.actionCompletion?.let { c ->
            val p = prev.actionCompletion
            out += "说到做到 ${pct(c)}（定了 ${cur.actionsChecked} 个）" + (p?.let { "，上周 ${pct(it)}" } ?: "")
        } ?: out.add("这周没有回报过下一步动作——先从每天一个开始")
        cur.types.take(3).forEach { t ->
            val pt = prev.types.firstOrNull { it.type == t.type }
            val line = "${t.type}：正确率 ${pct(t.accuracy)}，中位 ${t.medianMs / 1000} 秒" + (pt?.let { "（上周 ${pct(it.accuracy)}）" } ?: "")
            val gap = if (t.reviewedAccuracy != null && t.unreviewedAccuracy != null)
                "；做了终审 ${pct(t.reviewedAccuracy)}，没做 ${pct(t.unreviewedAccuracy)}" else ""
            out += line + gap
        }
        if (cur.attempts > 0) out += "抢答 ${cur.rushed} 次（共 ${cur.attempts} 题）" + if (prev.attempts > 0) "，上周 ${prev.rushed} 次" else ""
        return out
    }

    private fun pct(x: Double) = "${(x * 100).toInt()}%"
    private fun sign(x: Int) = if (x >= 0) "+$x" else "$x"
}
