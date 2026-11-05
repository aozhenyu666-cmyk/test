package com.yishou.app.schulte

/**
 * 舒尔特表：25 格乱序摆 1–25，按顺序一路点下去，看得多快、漏没漏。
 *
 * 只做本机能算的部分：怎么摆、点得对不对、成绩怎么算。计时和界面在 ui/SchulteScreen。
 *
 * 摆法是“真打乱”而不是固定几套死表：同一个格子重开不会一样，
 * 想背答案就得背整张表——也就没有背的意义了。
 */
data class SchulteBoard(val order: List<Int>, val size: Int = 5) {
    val total get() = size * size

    /** 第 index 格该点哪个数（从 1 数起） */
    fun cellAt(index: Int): Int = order[index]

    /** 现在该点的数 */
    fun nextOf(tapped: Int): Int = tapped + 1
}

/** 点一下的结果 */
sealed interface TapResult {
    /** 点对了：点到的是 next，下一该点 next+1 */
    data class Hit(val next: Int, val finished: Boolean) : TapResult
    /** 点错了：点到的不是该点的那个 */
    data class Miss(val expected: Int, val actual: Int) : TapResult
    /** 早就点过了 / 点的是空格，不算错也不推进 */
    data object Ignored : TapResult
}

object Schulte {

    /** 一局最多允许错几次还继续玩；超过只是继续玩，不判死 */
    const val MISS_SOFT_LIMIT = 3

    /** 摆一局。rand 每个数取一次 0..n-1，由调用方传 kotlin.random 好复现测试 */
    fun deal(size: Int = 5, shuffle: (MutableList<Int>) -> Unit): SchulteBoard {
        require(size >= 2) { "至少 2×2" }
        val nums = (1..size * size).toMutableList()
        shuffle(nums)
        return SchulteBoard(nums, size)
    }

    /**
     * 点第 number 格（值是 value）。tapped 是已经点对的个数。
     * 点错不重置进度——舒尔特表的训练目的是“看全”，不是罚重来。
     */
    fun tap(board: SchulteBoard, tapped: Int, value: Int): TapResult {
        val expected = tapped + 1
        return when {
            value == expected -> TapResult.Hit(next = expected, finished = expected >= board.total)
            value <= tapped -> TapResult.Ignored
            else -> TapResult.Miss(expected = expected, actual = value)
        }
    }

    /**
     * 成绩换算成分数 0–100：越快越高，漏点扣分。
     *
     * 参考值：25 格 20 秒以内算很快（一般成年人 25–40 秒，练熟了 12–20 秒）。
     * 40 秒以上按 40 秒算底分，避免一次手滑把分数打到负。
     */
    fun score(millis: Long, misses: Int, total: Int = 25): Int {
        if (millis <= 0L) return 0
        val seconds = millis / 1000.0
        // 每格平摊的参考秒数：25 格 20 秒 → 0.8 秒/格
        val perCell = 0.8 * (total / 25.0)
        val best = perCell * total
        val slowest = best * 2
        val speed = 1.0 - ((seconds - best) / (slowest - best)).coerceIn(0.0, 1.0)
        val penalty = (misses * 3).coerceAtMost(30)
        return ((speed * 70 + 30) - penalty).coerceIn(0.0, 100.0).toInt()
    }

    /** 给一句话评价：练到什么程度了 */
    fun verdict(millis: Long, misses: Int, total: Int = 25): String {
        val seconds = millis / 1000.0
        val perCell = seconds / total
        return when {
            misses == 0 && perCell <= 0.7 -> "又准又快"
            misses == 0 && perCell <= 1.2 -> "稳"
            misses <= 2 && perCell <= 1.6 -> "还行，眼睛再放大一圈"
            else -> "慢下来看整片，不要一个格一个格找"
        }
    }
}
