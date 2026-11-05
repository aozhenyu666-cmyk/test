package com.yishou.app.judge

import com.yishou.app.think.Words

enum class ProbeAction { NONE, FREEZE_15S, ASK_FRAGMENT }
fun probeAction(trace: ProbeTrace, answer: String, passed: Boolean): ProbeAction {
    val ttft = trace.firstInputAt?.minus(trace.shownAt) ?: return ProbeAction.NONE
    return when {
        ttft in 0L until 3_000L && !passed -> ProbeAction.FREEZE_15S
        ttft > 40_000L && Words.substance(answer) < 15 -> ProbeAction.ASK_FRAGMENT
        else -> ProbeAction.NONE
    }
}
