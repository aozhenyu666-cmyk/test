package com.yishou.app.healthwire

import android.content.Context
import android.util.Log
import com.yishou.app.log.RunLog
import com.yishou.app.system.Notifications

/**
 * 紧急暂停（规范 §6 / 报告 §3.6）。
 *
 * 必须满足的四条：
 *  1. **本地可达**：一次点击就到，不走网络、不问模型、不等字数、不等两小时；
 *  2. **不依赖网络与模型**：只碰本地文件 + 本地事实；
 *  3. **立即**停止本轮自动锁屏：把本地规则全部标 STOPPED 并落盘，同时让本地仲裁器忘记在途事件；
 *  4. **记录原因与使用时间**：写运行日志 + 本地时间戳，界面能显示「什么时候用过」。
 *
 * 与「正常提前结束监管」（窗口的冷静期 + 二次确认）**分开**：那个流程在 window/WindowViewModel 里，
 * 这里不动它、不合并它（规范 §6 最后一条）。
 *
 * 传播方面：一手只能确定**自己**停了。给哨兵发的那条同步如果没回执，界面必须显示「暂停未确认」，
 * 不能说「四个应用都停了」（规范 §6）。
 */
/**
 * 停止动作的落点。生产里由 [PolicyStore] 实现（真落盘），单测用内存假件，
 * 这样「停了之后 LocalGuard 还会不会锁」这件事可以在 JVM 上直接钉住。
 */
interface StopSink {
    fun load(): LocalPolicy.Doc
    /** 把所有规则标 STOPPED 并原子落盘；返回是否写成功。 */
    fun stopAll(nowWall: Long): Boolean
    /** 单独的暂停标记（窗口翻页 / 进程重启都不丢）；返回是否写成功。 */
    fun markEmergencyStopped(nowWall: Long, reason: String, bySentinel: Boolean): Boolean
    /** 用户恢复：清掉暂停标记。 */
    fun clearEmergencyStopped(): Boolean
    /** 当前是否处于暂停。**LocalGuard 的硬前置条件**。 */
    fun emergencyStopped(): Boolean
}

/** [PolicyStore] 就是生产环境的落点。 */
fun PolicyStore.asStopSink(): StopSink = object : StopSink {
    override fun load(): LocalPolicy.Doc = this@asStopSink.load()
    override fun stopAll(nowWall: Long): Boolean = this@asStopSink.stopAll(nowWall)
    override fun markEmergencyStopped(nowWall: Long, reason: String, bySentinel: Boolean): Boolean =
        this@asStopSink.markEmergencyStopped(nowWall, reason, bySentinel)
    override fun clearEmergencyStopped(): Boolean = this@asStopSink.clearEmergencyStopped()
    override fun emergencyStopped(): Boolean = this@asStopSink.emergencyStopped()
}

object EmergencyStop {

    private const val TAG = "EmergencyStop"

    /** 暂停记录：什么时候、因为什么。 */
    data class Record(val atWall: Long, val reason: String, val propagated: Boolean, val note: String)

    /**
     * 落盘下来的暂停标记（[PolicyStore.emergencyRecord] 读它）。
     * 进程重启后界面靠它说出「什么时候、为什么、是不是哨兵传来的」。
     */
    data class PauseMark(val atWall: Long, val reason: String, val bySentinel: Boolean)

    /**
     * 一次「停止」的真实结论。给哨兵的 emergencyStop 回包就用它填，界面文案也用它。
     *
     * @param ok 本地是否真的停了（写完再读一遍核对，不是「调用了就算」）
     * @param policyIds 已处于 STOPPED 的规则（摘要，给哨兵显示「已确认停止」）
     * @param alreadyStopped 之前就已经是暂停状态（幂等：重复调用仍 ok=true）
     */
    data class Outcome(
        val ok: Boolean,
        val policyIds: List<String>,
        val atWall: Long,
        val revision: Int,
        val alreadyStopped: Boolean,
        val note: String,
    )

    /**
     * **纯逻辑 + 一次落盘**：立即停止本地自动锁屏，幂等。
     *
     * 关键点（规范 §6 / 报告 §8）：
     *  - 只碰本地文件，**不联网、不请求模型、不等用户写字、不等冷静期**；
     *  - 写完**重新读一遍**核对（`ok` 只表示「真的落盘且规则确实都是 STOPPED」）；
     *  - 已经全部 STOPPED 时**不再写盘**，但仍然返回 ok=true（幂等）；
     *  - 没有规则时也算停住（无可锁的规则 = 停），同样 ok=true。
     */
    fun stopLocal(sink: StopSink, nowWall: Long, reason: String = "", bySentinel: Boolean = false): Outcome {
        val before = sink.load()
        val already = before.policies.isNotEmpty() && before.policies.all { it.emergencyState == "STOPPED" }
        val saved = if (already) true else try {
            sink.stopAll(nowWall)
        } catch (e: Exception) {
            Log.w(TAG, "停止本地规则时写盘失败", e)
            false
        }
        // 独立暂停标记：窗口翻页会换 policyId、进程重启会丢内存事实，只有这个标记能挡住
        // 「暂停之后又被新规则带着自动锁屏」。没有规则时也必须写。
        val marked = try {
            sink.markEmergencyStopped(nowWall, reason, bySentinel)
        } catch (e: Exception) {
            Log.w(TAG, "写紧急暂停标记失败", e)
            false
        }
        val after = sink.load()
        val stoppedIds = after.policies.filter { it.emergencyState == "STOPPED" }.map { it.policyId }
        val allStopped = after.policies.isEmpty() || after.policies.all { it.emergencyState == "STOPPED" }
        val flagOn = try {
            sink.emergencyStopped()
        } catch (e: Exception) {
            false
        }
        val ok = saved && allStopped && marked && flagOn
        val note = when {
            !saved -> "本地规则写入失败，未能确认停止"
            !marked || !flagOn -> "暂停标记写入失败，未能确认停止"
            after.policies.isEmpty() -> "当前没有本地规则（无可锁项）"
            allStopped -> "本地规则已全部停止（${stoppedIds.size} 条）"
            else -> "仍有规则未标为停止：${after.policies.filter { it.emergencyState != "STOPPED" }.map { it.policyId }}"
        }
        return Outcome(
            ok = ok,
            policyIds = stoppedIds,
            atWall = nowWall,
            revision = after.revision,
            alreadyStopped = already,
            note = note,
        )
    }

    @Volatile private var record: Record? = null

    fun last(): Record? = record

    /**
     * 「现在是否处于紧急暂停」的唯一判定。**tick() / snapshot() / 界面 / 单测共用这一处**，
     * 避免几处各写一遍然后漂移（漂移的后果就是「暂停了但兜底还在锁」）。
     *
     * 三条来源任一成立即算暂停：
     *  1. [memoryPaused]：本次进程内按下（`Facts.paused`）；
     *  2. [doc] 里任一规则的 `emergencyState == STOPPED`（规范 §4 的字段）；
     *  3. [storeFlag]：独立持久化标记 `filesDir/emergency-stop.json`。
     *
     * 第 3 条不可省：窗口翻页后 `RuleBuilder` 会生成**新 policyId** 的规则，旧 STOPPED 规则不再存在；
     * 进程重启也会清掉内存事实。只有独立标记能让暂停跨这两件事存活。
     *
     * 设计决定（captain 裁决 2026-10-25）：一手保持「到用户点恢复为止」的保守语义，
     * **不**随窗口结束自动失效——宁可少锁，不可在用户按了停止之后换个窗口又开始锁。
     * 代价是界面有义务显眼地说清「暂停仍在生效」。
     */
    fun isPaused(memoryPaused: Boolean, doc: LocalPolicy.Doc, storeFlag: Boolean): Boolean =
        memoryPaused || LocalPolicy.anyStopped(doc) || storeFlag

    /**
     * 立即停止自己的自动锁屏。
     *
     * @param reason 用户选/写的理由（可以空，不强制）。
     * @return 真实结论：本地一定停了；[Record.propagated] 只表示「同步给哨兵这件事有没有回执」。
     */
    fun stopNow(context: Context, reason: String): Record {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val clean = reason.trim().take(200)

        // 1) 本地规则立即标 STOPPED 并原子落盘（先落盘、再读回核对，最后才对外宣称）。
        val outcome = stopLocal(YishouGuard.policies(app).asStopSink(), now, clean, bySentinel = false)
        // 2) 本地仲裁器忘掉在途事件：不留下「已锁过一次」的记忆挡住后面的正常执行。
        YishouGuard.resetArbitration()
        // 3) 暂停状态进本地事实，健康 ack 里能带出去（哨兵据此知道一手自己停了）。
        //    注意这里跟着**真实落盘结果**走，写不进去就不谎称已暂停。
        Facts.setPaused(outcome.ok)
        Facts.onReceipt(if (outcome.ok) "PAUSED" else "PAUSE_FAILED", now)

        // 4) 尽量同步给哨兵；没回执就如实标「未确认」。
        var propagated = false
        try {
            if (outcome.ok) {
                SentinelLink.syncPolicy(app, YishouGuard.policies(app).load())
                // syncPolicy 是后台异步的，这里只能给「已发出」级别的结论；
                // 真正的确认要看 SentinelLink.state() 的 acceptedRevision 是否跟上。
                propagated = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "紧急暂停同步哨兵失败", e)
        }

        val rec = Record(
            atWall = now,
            reason = clean,
            propagated = propagated,
            note = "一手自己的自动锁屏已停止（${outcome.note}）".takeIf { outcome.ok }
                ?: "本地规则写入失败：${YishouGuard.policies(app).lastError}",
        )
        record = rec
        RunLog.e(TAG, "紧急暂停：${rec.note}；理由=${clean.ifBlank { "（未填）" }}；同步哨兵=${if (propagated) "已确认" else "未确认"}")
        try {
            Notifications.showReminder(
                app,
                Notifications.ID_NUDGE,
                "已紧急暂停自动锁屏",
                "一手自己的自动锁屏已停止（${timeText(now)}）。其他应用是否也停了要看它们的回执，不能替它们担保。",
                com.yishou.app.MainActivity::class.java,
            )
        } catch (e: Exception) {
            Log.w(TAG, "发暂停通知失败", e)
        }
        return rec
    }

    /** 用户回来继续（下一步：解除 STOPPED，规则本身没被删）。 */
    fun resume(context: Context) {
        val app = context.applicationContext
        val store = YishouGuard.policies(app)
        val ok = store.resumeAll()
        if (ok) store.clearEmergencyStopped()
        record = null
        Facts.setPaused(false)
        Facts.onReceipt(if (ok) "RESUMED" else "RESUME_FAILED", System.currentTimeMillis())
        RunLog.e(TAG, "紧急暂停解除：${if (ok) "规则恢复执行" else "写入失败"}")
    }

    /** 当地时间文案（只用于界面/通知，不参与判断）。 */
    private fun timeText(at: Long): String = try {
        val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        f.format(java.util.Date(at))
    } catch (e: Exception) {
        ""
    }
}
