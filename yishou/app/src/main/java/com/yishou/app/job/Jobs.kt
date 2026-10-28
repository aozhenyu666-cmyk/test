package com.yishou.app.job

import com.yishou.app.data.JobMode
import com.yishou.app.data.JobStep
import com.yishou.app.data.StepResult
import com.yishou.app.think.Words

/**
 * 做事的拆法和统计。都是本机算，不经过模型。
 * 一件事 = 一串步；当前步 = 第一个还没做完的；每步记开始、结束、结果和一句话。
 */
object Jobs {

    /** 认得的量词：“10 道题”“3 篇”“5 页”…… */
    private val COUNTERS = listOf("道", "题", "篇", "页", "章", "节", "组", "套", "个", "次", "张", "段", "条", "份", "件", "遍", "轮")
    private val COUNT = Regex("(\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*(${COUNTERS.joinToString("|")})")

    const val MAX_STEPS = 60

    /** 做完一步要回答的一问（轮着问）：判断、预判、复盘 */
    val REFLECT = listOf("这一步最关键的判断是什么？", "下一步你预计会卡在哪？为什么？", "如果重来，这一步怎么做更快？")

    /** 从“完成 10 道题”里认出份数和量词；认不出返回 null */
    fun count(title: String): Pair<Int, String>? {
        val m = COUNT.find(title) ?: return null
        val n = Words.numbers(m.groupValues[1]).firstOrNull()?.toInt() ?: return null
        if (n !in 2..MAX_STEPS) return null
        return n to m.groupValues[2]
    }

    /**
     * 拆步骤：
     * - 按份数：“10 道题” → 第 1 道 … 第 10 道；
     * - 自己列：每行一步（去掉行首的 1. ① - 之类）；
     * - 走一步看一步：只有第一步（可以先空着，开始时再说）。
     */
    fun plan(mode: String, title: String, lines: String, first: String = ""): List<String> = when (mode) {
        JobMode.COUNT -> {
            val (n, unit) = count(title) ?: (1 to "步")
            (1..n).map { "第 $it $unit" }
        }
        JobMode.LIST -> lines.lines()
            .map { it.replace(Regex("^\\s*(?:\\d{1,2}\\s*[.、．)）]|[（(]\\d{1,2}[)）]|[①②③④⑤⑥⑦⑧⑨⑩]|[-•·*])\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_STEPS)
        else -> listOf(first.trim().ifEmpty { "第一步" })
    }

    /** 推荐拆法：标题里有份数就按份数，否则走一步看一步 */
    fun suggestMode(title: String): String = if (count(title) != null) JobMode.COUNT else JobMode.OPEN

    fun current(steps: List<JobStep>): JobStep? = steps.sortedBy { it.idx }.firstOrNull { it.doneAt == null }

    fun progress(steps: List<JobStep>): Float = if (steps.isEmpty()) 0f else steps.count { it.doneAt != null }.toFloat() / steps.size

    fun duration(s: JobStep): Long? {
        val a = s.startedAt ?: return null
        val b = s.doneAt ?: return null
        return (b - a).coerceAtLeast(0)
    }

    data class Summary(
        val total: Int,
        val done: Int,
        val ok: Int,
        val miss: Int,
        val stuck: Int,
        val skipped: Int,
        /** 实际用时（从第一步开始到最后一步结束），毫秒 */
        val spentMs: Long,
        /** 做过的步平均用时 */
        val avgMs: Long?,
        /** 用时最长的一步 */
        val slowest: JobStep?,
        /** 和估计的差：正数是超了几成，负数是快了几成（百分比） */
        val overrun: Int?,
    )

    fun summary(steps: List<JobStep>, estimateMinutes: Int?): Summary {
        val done = steps.filter { it.doneAt != null }
        val timed = done.filter { it.result != StepResult.SKIP }.mapNotNull { s -> duration(s)?.let { s to it } }
        val start = steps.mapNotNull { it.startedAt }.minOrNull()
        val end = done.mapNotNull { it.doneAt }.maxOrNull()
        val spent = if (start != null && end != null) (end - start).coerceAtLeast(0) else 0
        val over = estimateMinutes?.takeIf { it > 0 && spent > 0 }?.let { est ->
            ((spent / 60_000.0 - est) / est * 100).toInt()
        }
        return Summary(
            total = steps.size,
            done = done.size,
            ok = done.count { it.result == StepResult.OK },
            miss = done.count { it.result == StepResult.MISS },
            stuck = done.count { it.result == StepResult.STUCK },
            skipped = done.count { it.result == StepResult.SKIP },
            spentMs = spent,
            avgMs = if (timed.isEmpty()) null else timed.sumOf { it.second } / timed.size,
            slowest = timed.maxByOrNull { it.second }?.first,
            overrun = over,
        )
    }

    /** 3分20秒 / 1小时5分 / 40秒 */
    fun showDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600}小时${(s % 3600) / 60}分"
            s >= 60 -> "${s / 60}分${(s % 60).toString().padStart(2, '0')}秒"
            else -> "${s}秒"
        }
    }

    /** 收尾的一行：做成几步、卡在哪、和估计差多少 */
    fun line(s: Summary): String = buildList {
        add("${s.done}/${s.total} 步")
        if (s.ok > 0 || s.miss > 0) add("做成 ${s.ok}")
        if (s.miss > 0) add("没做成 ${s.miss}")
        if (s.stuck > 0) add("卡住 ${s.stuck}")
        if (s.spentMs > 0) add("用时 ${showDuration(s.spentMs)}")
        s.avgMs?.let { add("平均每步 ${showDuration(it)}") }
        s.overrun?.let { add(if (it >= 0) "比估计多 $it%" else "比估计快 ${-it}%") }
    }.joinToString(" · ")
}
