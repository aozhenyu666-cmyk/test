package com.yishou.app.window

/**
 * 窗口里一手没人应时的升级梯子（从出题或最后一次动静算起）：
 * 第 1 级：remindFirst 分钟后朗读卡点和起手提示；
 * 第 2 级：再过 remindSecond 分钟再读一次，标“未回应”；
 * 第 3 级起：再每过 pull 分钟把窗口页拉回前台（不管你在哪个应用），最多 [MAX_PULLS] 次。
 */
object IdleLadder {
    const val MAX_PULLS = 3
    const val FIRST_PULL_LEVEL = 3

    fun level(idleMs: Long, first: Int, second: Int, pull: Int, pullEnabled: Boolean): Int {
        val min = 60_000L
        val t1 = first * min
        val t2 = t1 + second * min
        return when {
            idleMs < t1 -> 0
            idleMs < t2 -> 1
            !pullEnabled -> 2
            else -> 2 + ((idleMs - t2) / (pull.coerceAtLeast(1) * min)).toInt().coerceAtMost(MAX_PULLS)
        }
    }

    fun isPull(level: Int) = level >= FIRST_PULL_LEVEL
}
