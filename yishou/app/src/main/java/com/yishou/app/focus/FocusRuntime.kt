package com.yishou.app.focus

/** 名字只供计划展示；拦截只认开始时确认过的包名。 */
data class FocusRun(
    val session: FocusSession,
    val allowedPackages: Set<String> = emptySet(),
    val messaging: Set<String> = emptySet(),
    val alertedRounds: Set<Int> = emptySet(),
    val endNotified: Boolean = false,
)

object FocusRuntime {
    /** null 才轮到原来的收费站；专注中的 ALLOW 也直接返回，不查放行。 */
    fun gate(run: FocusRun?, now: Long, pkg: String, self: String, system: Set<String>, static: Set<String>): FocusRules.Decision? =
        run?.takeIf { it.session.active(now) }?.let {
            FocusRules.decide(pkg, self, it.allowedPackages, it.messaging, system, static)
        }

    /** 换节奏截断的那一组也结束了；零休息时照样能找到刚结束的一组。 */
    fun completedRounds(s: FocusSession, now: Long): List<Int> {
        val end = minOf(now, s.endedAt ?: s.endsAt, s.endsAt)
        val changes = listOf(Retune(s.startedAt, s.plan.workMin, s.plan.restMin)) + s.retunes.sortedBy { it.at }
        val out = mutableListOf<Int>()
        var round = 0
        changes.forEachIndexed { i, r ->
            val segmentEnd = minOf(changes.getOrNull(i + 1)?.at ?: s.endsAt, s.endsAt)
            var at = r.at
            while (at < segmentEnd) {
                round++
                if (minOf(at + r.workMin * 60_000L, segmentEnd) <= end) out += round
                at += (r.workMin + r.restMin) * 60_000L
            }
        }
        return out
    }

    fun totalRounds(s: FocusSession): Int {
        val stop = minOf(s.endedAt ?: s.endsAt, s.endsAt)
        return s.copy(endedAt = null).phase((stop - 1).coerceAtLeast(s.startedAt)).round
    }

    fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0) + 999) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
