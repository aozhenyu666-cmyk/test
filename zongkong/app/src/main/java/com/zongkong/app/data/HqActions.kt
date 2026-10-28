package com.zongkong.app.data

import android.content.Context
import com.zongkong.app.system.Buzz
import com.zongkong.app.system.Notifications
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqSession
import com.zongkong.core.hq.HqStep
import com.zongkong.core.hq.HqTrigger
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.WorkOps
import java.time.Instant
import kotlin.random.Random

/** 总控时间的操作：进入、下一环节、回去再修、结束、紧急退出。 */
class HqActions(private val context: Context, private val store: Store, private val actions: Actions) {
    private fun now() = System.currentTimeMillis()

    fun active(): HqSession? = store.hq.value.active

    /** 进入总控时间（已经在里面就返回那一段）。 */
    fun start(trigger: HqTrigger, minutes: Int = store.config.value.hq.minutes): HqSession {
        active()?.let { return it }
        val s = Hq.start("h" + now().toString(36), trigger, store.config.value.hq, now(), minutes)
        store.updateHq { it.put(s) }
        Buzz.stop(context)
        Notifications.cancelHq(context)
        return s
    }

    private fun update(f: (HqSession) -> HqSession) {
        val s = active() ?: return
        store.updateHq { it.put(f(s)) }
    }

    fun advance(note: String = "") = update { Hq.advance(it, now(), note) }

    fun revisit(step: HqStep) = update { Hq.revisit(it, step, now()) }

    fun note(step: HqStep, text: String) = update { it.copy(notes = it.notes + (step.name to text)) }

    fun triaged(n: Int = 1) = update { it.copy(triaged = it.triaged + n) }

    /**
     * 正常结束：记一条“验收”记录（同步到 Notion），交给“日终验收”关卡（没交过的话），解锁。
     * 返回验收结果的一句话。
     */
    suspend fun finish(summary: String): String {
        val s = active() ?: return "没有进行中的总控时间"
        val done = Hq.finish(s, now(), summary)
        store.updateHq { it.put(done) }
        store.touch()
        Buzz.stop(context)
        val report = Hq.report(done) + "\n\n" + com.zongkong.core.plan.DayReport.text(actions.dayReport())
        val t = now()
        store.updateWork { w ->
            WorkOps.create(w, Kind.NOTE, mapOf(
                F.TITLE to "总控时间 " + Instant.ofEpochMilli(t).atZone(store.zone).toLocalDate(),
                F.TYPE to NoteType.REVIEW.label, F.RAW to report,
                F.DATE to Instant.ofEpochMilli(t).atZone(store.zone).toLocalDate().toString(),
            ), t, Random.Default).first
        }
        actions.work.kick()
        val gate = store.status().gates.firstOrNull { it.gate.id == "hq_review" }
        if (gate == null || gate.done) return "已封存"
        return when (val r = actions.submit("hq_review", report)) {
            is Actions.SubmitResult.Judged -> if (r.verdict.pass) "今天的清单已交" else "日终验收：${r.verdict.feedback}"
            is Actions.SubmitResult.Unavailable -> if (r.degradedLeft > 0) {
                (actions.submitDegraded("hq_review", report, r.reason) as? Actions.SubmitResult.Judged)?.verdict?.let { if (it.pass) "日终验收（降级）通过" else it.feedback } ?: r.reason
            } else "AI 验收不可用：${r.reason}"
            // 日终验收还没开放（比如提前做了总控时间）：封存照样算，验收到点再交
            is Actions.SubmitResult.Refused -> "已封存。日终验收：${r.message}，到时候用这次的记录交。"
        }
    }

    /** 紧急退出：要写原因（至少 6 个字），记下来。返回错误说明，成功返回 null。 */
    fun exitEarly(reason: String, systemEnding: Boolean = false): String? {
        val s = active() ?: return null
        if (s.trigger == HqTrigger.UNANSWERED && !systemEnding) return actions.control.deferHq(reason)
        if (reason.trim().length < 6) return "写清楚为什么要现在退出（至少 6 个字）"
        store.updateHq { it.put(Hq.exitEarly(s, now(), reason.trim())) }
        Buzz.stop(context)
        return null
    }
}
