package com.yishou.pc.core

/** 守门页要显示的东西。 */
data class GateInfo(
    val category: Category,
    /** 在专注时段里：一律不放（只有急事） */
    val focus: Boolean,
    val used: Int,
    val budget: Int,
    /** 这次最多能放几分钟；0 表示今天不能再放了 */
    val maxGrant: Int,
    /** 放行按钮要等几秒 */
    val cooldownSec: Int,
    val emergencyLeft: Int,
    /** 今天第几次想打开这一类 */
    val attempt: Int,
    /** 刚到时间的那次放行（显示“你说看完回来做……”） */
    val timeUp: Pass?,
    /** 要先回答“做了吗”的那次放行 */
    val followUp: Pass?,
) {
    val canGrant: Boolean get() = !focus && maxGrant >= 1

    /** 可选的时长：5、10、15、20、30，不超过这次的上限；上限本身不在里面时补上 */
    val choices: List<Int>
        get() {
            if (!canGrant) return emptyList()
            val base = listOf(5, 10, 15, 20, 30).filter { it <= maxGrant }
            return if (maxGrant in base || maxGrant > 30) base else base + maxGrant
        }
}

sealed interface Verdict {
    data class Allow(val pass: Pass) : Verdict
    data class Gate(val info: GateInfo) : Verdict
}

object Gatekeeper {
    const val EMERGENCY_MINUTES = 5
    const val EMERGENCY_MIN_CHARS = 30
    const val WHAT_MIN_CHARS = 2
    const val THEN_MIN_CHARS = 4
    const val QUIT_MIN_CHARS = 10

    /**
     * 前台是 category 这一类时：有没在专注时段、有没有还没到期的放行。
     * 专注时段优先：之前放的行在专注时段里也不算。
     */
    fun check(categoryId: String, now: Long, day: DayState, prefs: Prefs, focus: Boolean): Verdict {
        val cat = prefs.category(categoryId) ?: Category(categoryId, categoryId, 0, 1)
        if (!focus) day.activePass(categoryId, now)?.let { return Verdict.Allow(it) }
        val used = day.used[categoryId] ?: 0
        val remaining = (cat.dailyMinutes - used).coerceAtLeast(0)
        val granted = day.grants[categoryId] ?: 0
        return Verdict.Gate(
            GateInfo(
                category = cat,
                focus = focus,
                used = used,
                budget = cat.dailyMinutes,
                maxGrant = minOf(cat.maxPass, remaining),
                cooldownSec = cooldown(granted, prefs),
                emergencyLeft = (prefs.emergencyPerDay - day.emergencies).coerceAtLeast(0),
                attempt = (day.gates[categoryId] ?: 0) + 1,
                timeUp = day.justExpired(categoryId, now),
                followUp = day.pendingFollowUp(now),
            ),
        )
    }

    /** 冷静期：今天这一类每多放一次，多等一会儿。 */
    fun cooldown(grantedToday: Int, prefs: Prefs): Int =
        minOf(prefs.cooldownBase + prefs.cooldownStep * grantedToday, prefs.cooldownMax)

    /** 只数文字和数字，空格、标点不算。 */
    fun chars(text: String): Int = text.codePoints().filter { Character.isLetterOrDigit(it) }.count().toInt()

    fun intentionOk(what: String, then: String) = chars(what) >= WHAT_MIN_CHARS && chars(then) >= THEN_MIN_CHARS
}
