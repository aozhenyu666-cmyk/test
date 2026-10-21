package com.moling.app

import com.moling.core.BrainFacts
import com.moling.core.flow.Flow
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource

/*
 * 手机这边能看到的事实：时间、静默、前台应用（M10）。
 *
 * 主线、水道、总控状态由大脑（BrainFacts.merge）补上，自己不猜；
 * 拿不到的写进 unknown，导演就知道自己瞎了哪只眼。
 * 没开「使用情况访问」时只降级一项，其他照常。
 */
class PhoneFacts(
    private val foreground: () -> Foreground?,
    private val usageAllowed: () -> Boolean,
    private val status: () -> ZkStatus?,
) : FactsSource {

    override fun facts(now: Long): Facts {
        val minute = Flow.minuteOf(now)
        val allowed = runCatching { usageAllowed() }.getOrDefault(false)
        val fg = if (allowed) runCatching { foreground() }.getOrNull() else null
        val unknown = buildList {
            add("健康")
            when {
                !allowed -> add("前台应用（没开使用情况访问）")
                fg == null -> add("前台应用（读不到）")
            }
        }
        return Facts(
            now = now,
            minute = minute,
            // 23:30–07:00 算静默：导演夜里不弹卡。
            quiet = minute >= 23 * 60 + 30 || minute < 7 * 60,
            foregroundPkg = fg?.pkg.orEmpty(),
            foregroundLabel = fg?.label.orEmpty(),
            foregroundCategory = BrainFacts.category(fg?.pkg.orEmpty(), status()),
            foregroundMinutes = fg?.minutes ?: 0,
            unknown = unknown,
        )
    }
}
