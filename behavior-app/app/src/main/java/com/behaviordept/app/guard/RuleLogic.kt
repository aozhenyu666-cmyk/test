package com.behaviordept.app.guard

import com.behaviordept.app.data.Rule

/**
 * 防线规则的冷静期（PRD M5、设计原则 3）：
 * 收紧（降低上限、多管一个 App）立即生效；放宽（提高上限、少管一个 App、删除规则）要等 24 小时，期间可以撤回。
 */
object RuleLogic {
    const val COOLING_MS = 24 * 60 * 60 * 1000L

    /** 修改规则。一次修改里收紧的部分立刻生效，放宽的部分进入冷静期。 */
    fun edit(old: Rule, name: String, newLimit: Int, newPackages: List<String>, now: Long): Rule {
        val oldPkgs = old.packageList
        val wantPkgs = newPackages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // 立刻生效的部分：上限取更严的，App 取并集。
        val immediateLimit = minOf(old.dailyLimitMin, newLimit)
        val immediatePkgs = (oldPkgs + wantPkgs).distinct()
        val loosening = newLimit > old.dailyLimitMin || !wantPkgs.containsAll(oldPkgs)
        return old.copy(
            name = name.trim(),
            dailyLimitMin = immediateLimit,
            packages = immediatePkgs.joinToString(","),
            effectiveAt = if (immediateLimit != old.dailyLimitMin || immediatePkgs != oldPkgs) now else old.effectiveAt,
            pendingLimitMin = if (loosening) newLimit else null,
            pendingPackages = if (loosening) wantPkgs.joinToString(",") else null,
            pendingEffectiveAt = if (loosening) now + COOLING_MS else null,
            pendingDelete = false,
        )
    }

    /** 删除规则也是放宽：24 小时后才删。 */
    fun requestDelete(old: Rule, now: Long): Rule =
        old.copy(pendingDelete = true, pendingEffectiveAt = now + COOLING_MS, pendingLimitMin = null, pendingPackages = null)

    fun cancelPending(rule: Rule): Rule =
        rule.copy(pendingLimitMin = null, pendingPackages = null, pendingEffectiveAt = null, pendingDelete = false)

    /** 冷静期满了就生效。返回 null 表示规则应被删除。 */
    fun applyDue(rule: Rule, now: Long): Rule? {
        val at = rule.pendingEffectiveAt ?: return rule
        if (now < at) return rule
        if (rule.pendingDelete) return null
        return rule.copy(
            dailyLimitMin = rule.pendingLimitMin ?: rule.dailyLimitMin,
            packages = rule.pendingPackages ?: rule.packages,
            effectiveAt = at,
            pendingLimitMin = null,
            pendingPackages = null,
            pendingEffectiveAt = null,
        )
    }

    /** 规则管的 App 当天合计用时。 */
    fun minutesFor(rule: Rule, usageByPackage: Map<String, Int>): Int = rule.packageList.sumOf { usageByPackage[it] ?: 0 }

    /** 最近若干天里守住上限的天数。 */
    fun daysKept(dailyMinutes: List<Int>, limit: Int): Int = dailyMinutes.count { it <= limit }
}
