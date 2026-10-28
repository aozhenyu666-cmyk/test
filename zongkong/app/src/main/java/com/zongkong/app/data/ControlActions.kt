package com.zongkong.app.data

import android.content.Context
import com.zongkong.app.system.Buzz
import com.zongkong.app.system.Notifications
import com.zongkong.app.system.Reminders
import com.zongkong.core.DayClock
import com.zongkong.core.GatePhase
import com.zongkong.core.control.Control
import com.zongkong.core.control.DeferralKind

/** 缓行只能从用户界面使用；不交给模型，也不伪造验收。 */
class ControlActions(private val context: Context, private val store: Store, private val actions: Actions) {
    fun deferGate(gateId: String, reason: String = ""): String? {
        val now = System.currentTimeMillis()
        val status = store.status(now)
        val gate = status.gates.firstOrNull { it.gate.id == gateId } ?: return "没有这道关卡"
        if (gate.done) return "这道关卡已经完成"
        if (gate.phase == GatePhase.NOT_OPEN) return "关卡还没开放，暂时不用缓行"
        return grant(DeferralKind.GATE, gateId, gate.gate.title, reason, gate.gate)
    }

    fun deferHq(reason: String = ""): String? {
        val now = System.currentTimeMillis()
        val session = store.hq.value.active ?: return "没有进行中的总控时间"
        if (session.suspendedUntil > now) return "已经在缓行中，原时间到后恢复"
        val error = grant(DeferralKind.HQ, session.id, "总控时间 / 日终清单", reason)
        if (error != null) return error
        val until = store.control.value.deferrals.last().until
        store.updateHq { it.put(com.zongkong.core.hq.Hq.suspend(session, now, until)) }
        Buzz.stop(context)
        Notifications.cancelHq(context)
        Reminders.reschedule(context)
        return null
    }

    fun resumeHq() {
        val now = System.currentTimeMillis()
        val session = store.hq.value.active ?: return
        store.updateHq { it.put(com.zongkong.core.hq.Hq.resume(session, now)) }
        store.updateControl { b -> b.copy(deferrals = b.deferrals.map {
            if (it.kind == DeferralKind.HQ && it.target == session.id && it.until > now) it.copy(until = now) else it
        }) }
        store.logWake("主动恢复总控时间，今日缓行额度仍已使用")
        Reminders.reschedule(context)
    }

    fun deferAction(key: String, reason: String = ""): String? {
        val task = store.work.value.rec(key) ?: return "没有这一步行动"
        if (task.kind != com.zongkong.core.work.Kind.ACTION) return "只能延后行动任务"
        if (!task.actionStatus.open) return "这一步已经结束"
        val error = grant(DeferralKind.ACTION, key, task.title, reason)
        if (error == null) actions.work.snooze(key, Control.GRACE_MINUTES)
        return error
    }

    private fun grant(kind: DeferralKind, key: String, title: String, reason: String, gate: com.zongkong.core.Gate? = null): String? {
        val now = System.currentTimeMillis()
        var error: String? = null
        store.updateControl { book ->
            Control.defer(book, DayClock.dateKey(now, store.zone), kind, key, title, now, reason, gate)
                .getOrElse { error = it.message; book }
        }
        if (error == null) {
            store.logWake("使用今日缓行：$title，${Control.GRACE_MINUTES} 分钟后恢复，未算完成")
            Reminders.reschedule(context)
        }
        return error
    }
}
