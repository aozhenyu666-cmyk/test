package com.moling.core.cycle

import com.moling.core.flow.Flow
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.JobStatus
import com.moling.core.workspace.WorkbenchState

/*
 * 她主动准备第一块（v13 W10 T7）：他开了这个开关，案板上正在做的那件事有材料、却还没有任何稿子，
 * 她就在预算内先替他做一份三点纲要放上案板（未采用），发一条通知。
 *
 * 规矩：
 *  - 开关默认关（宿主判断）；安静期、夜里（23:00–7:00）不做；
 *  - 只针对“正在做”的那一件，有材料、没稿子、没有在跑的作业；
 *  - 材料最后一次变动后至少等 [WAIT_MS]（给他自己动手的时间），超过 [STALE_MS] 就不管了（不翻旧账）；
 *  - 同一件事、同一版材料只准备一次（[key]）；
 *  - 预算：每天后台模型作业数、未读产物数都要有余量；
 *  - 产物不自动采用、不发总控、不改体系图。
 */
object ProactivePrep {
    const val WAIT_MS = 10 * 60_000L
    const val STALE_MS = 12 * 60 * 60_000L

    data class Decision(val focusId: String? = null, val key: String = "", val why: String)

    fun key(focusId: String, revision: Long) = "$focusId@$revision"

    fun pick(ws: WorkbenchState, now: Long, quiet: Boolean, budget: AutonomyBudget, done: Collection<String>): Decision {
        if (quiet) return Decision(why = "安静期")
        val minute = Flow.minuteOf(now)
        if (minute < 7 * 60 || minute >= 23 * 60) return Decision(why = "夜里不做")
        if (!budget.canJob()) return Decision(why = "今天的后台作业额度用完了")
        if (!budget.canUnread()) return Decision(why = "已经有没看的产物了")
        val f = ws.active() ?: return Decision(why = "案板上没有正在做的事")
        val ms = ws.materialsOf(f)
        if (ms.isEmpty()) return Decision(why = "这件事还没有材料")
        if (ws.artifactsOf(f.id).any { it.status != ArtifactStatus.ARCHIVED }) return Decision(why = "已经有稿子了")
        if (ws.jobs.any { it.focusId == f.id && (it.status == JobStatus.RUNNING || it.status == JobStatus.QUEUED) }) return Decision(why = "正在做")
        val k = key(f.id, f.revision)
        if (k in done) return Decision(why = "这一版材料已经准备过")
        val lastChange = (ms.maxOf { it.at }).coerceAtLeast(f.startedAt)
        if (now - lastChange < WAIT_MS) return Decision(why = "材料刚放上去，先等他自己动手")
        if (now - lastChange > STALE_MS) return Decision(why = "材料放了太久，不翻旧账")
        return Decision(f.id, k, "有材料、没稿子，替他先拆一份纲要")
    }
}
