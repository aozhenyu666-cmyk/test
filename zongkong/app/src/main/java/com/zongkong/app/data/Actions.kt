package com.zongkong.app.data

import android.content.Context
import com.zongkong.core.ApiResult
import com.zongkong.core.DayClock
import com.zongkong.core.DayOps
import com.zongkong.core.Defaults
import com.zongkong.core.Facts
import com.zongkong.core.GatePhase
import com.zongkong.core.LlmClient
import com.zongkong.core.LogEntry
import com.zongkong.core.NotionClient
import com.zongkong.core.Policy
import com.zongkong.core.ReportKind
import com.zongkong.core.TextCheck
import com.zongkong.core.ThinkSheet
import com.zongkong.core.Verdict
import com.zongkong.core.Verifier
import com.zongkong.core.VerifyMode
import com.zongkong.core.VerifyOutcome
import com.zongkong.core.Workspace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

/** 界面和后台服务共用的操作：交关卡、报到、随手记、紧急放行、同步 Notion。 */
class Actions(private val context: Context, private val store: Store) {
    val llm = LlmClient({ store.config.value.ai })
    val notion = NotionClient({ store.config.value.notion.token })
    private val verifier = Verifier(llm, notion)
    private val syncLock = Mutex()

    sealed interface SubmitResult {
        data class Judged(val verdict: Verdict) : SubmitResult
        data class Unavailable(val reason: String, val degradedLeft: Int) : SubmitResult
        data class Refused(val message: String) : SubmitResult
    }

    suspend fun submit(gateId: String, text: String): SubmitResult {
        val now = System.currentTimeMillis()
        val status = store.status(now)
        val gs = status.gates.firstOrNull { it.gate.id == gateId }
            ?: return SubmitResult.Refused("今天没有这道关卡")
        if (gs.done) return SubmitResult.Refused("这道关卡今天已经验收过了")
        if (gs.phase == GatePhase.NOT_OPEN) {
            return SubmitResult.Refused("还没开放，${DayClock.hhmm(gs.gate.openAt)} 开放")
        }
        val date = LocalDate.parse(status.date)
        val facts = Facts.build(status, store.today.value, store.zone)
        return when (val out = verifier.verify(gs.gate, text, store.config.value, date, store.zone, facts)) {
            is VerifyOutcome.Done -> {
                record(gateId, text, out.verdict)
                SubmitResult.Judged(out.verdict)
            }
            is VerifyOutcome.Unavailable -> SubmitResult.Unavailable(out.reason, if (out.canDegrade) status.degradedLeft else 0)
        }
    }

    suspend fun submitDegraded(gateId: String, text: String, reason: String): SubmitResult {
        val status = store.status()
        val gs = status.gates.firstOrNull { it.gate.id == gateId } ?: return SubmitResult.Refused("今天没有这道关卡")
        if (status.degradedLeft <= 0) return SubmitResult.Refused("今天的降级验收次数用完了")
        val verdict = Verifier.degraded(gs.gate, text, LocalDate.parse(status.date), reason)
        record(gateId, text, verdict)
        return SubmitResult.Judged(verdict)
    }

    private suspend fun record(gateId: String, text: String, verdict: Verdict) {
        val now = System.currentTimeMillis()
        store.updateToday(now) { day ->
            val gs = com.zongkong.core.Engine.evaluate(store.config.value, day, now, store.zone).gates.first { it.gate.id == gateId }
            if (gs.done) day else DayOps.submit(day, gs, text, verdict, now)
        }
        if (verdict.pass) {
            store.clearDraft(gateId)
            sync()
        }
    }

    /** 报到。返回错误说明，成功返回 null。 */
    suspend fun checkin(text: String): String? {
        if (TextCheck.effectiveChars(text) < 10) return "说清楚现在在做什么、接下来做什么，至少 10 个字"
        if (TextCheck.looksPadded(text)) return "认真写一句"
        store.updateToday { DayOps.checkin(it, text, System.currentTimeMillis()) }
        sync()
        return null
    }

    /** 随手记：先记在本机，配置了 Notion 信息收集库就立即写过去。 */
    suspend fun capture(text: String, tag: String): String {
        if (text.isBlank()) return "写点东西"
        val now = System.currentTimeMillis()
        store.updateToday(now) { DayOps.capture(it, text, tag, now, synced = false) }
        val n = store.config.value.notion
        if (!n.ready || n.inboxDb.isBlank()) return "已记在本机（还没设置 Notion 信息收集库）"
        val r = Workspace.capture(notion, n.inboxDb, text, tag, store.today.value.date)
        return when (r) {
            is ApiResult.Ok -> {
                store.updateToday { DayOps.markCaptureSynced(it, now) }
                "已存进 Notion 信息收集库"
            }
            is ApiResult.Err -> "已记在本机，写入 Notion 失败（稍后自动重试）：${r.message}"
        }
    }

    fun emergency(reason: String): String {
        val now = System.currentTimeMillis()
        var msg = ""
        store.updateToday(now) { day ->
            val status = com.zongkong.core.Engine.evaluate(store.config.value, day, now, store.zone)
            val (next, m) = DayOps.emergency(day, store.config.value, status, reason, now)
            msg = m
            next ?: day
        }
        return msg
    }

    /**
     * 把待写的日志、随手记写进 Notion。失败的留着下次再试。
     * 返回一句结果说明。
     */
    suspend fun sync(): String = syncLock.withLock {
        val cfg = store.config.value.notion
        if (!cfg.ready) return "还没配置 Notion"
        var ok = 0
        var failed: String? = null
        val days = store.history(3)
        for (d in days) {
            for (e in d.outbox) {
                for (target in e.targets) {
                    val db = when (target) {
                        LogEntry.TARGET_LOG -> cfg.logDb
                        LogEntry.TARGET_THINK -> cfg.thinkDb
                        else -> ""
                    }
                    if (db.isBlank()) {
                        // 没设置这个库：不用写了
                        store.updateDay(d.date) { DayOps.outboxDone(it, e.at, target) }
                        continue
                    }
                    val r = when (target) {
                        LogEntry.TARGET_THINK -> ThinkSheet.write(
                            notion, db, e, d.date, Defaults.methodOf(LocalDate.parse(d.date)).name,
                        )
                        else -> Workspace.log(notion, db, e, d.date)
                    }
                    when (r) {
                        is ApiResult.Ok -> {
                            ok++
                            store.updateDay(d.date) { DayOps.outboxDone(it, e.at, target) }
                        }
                        is ApiResult.Err -> failed = r.message
                    }
                    if (failed != null) break
                }
                if (failed != null) break
            }
            if (cfg.inboxDb.isNotBlank() && failed == null) {
                for (rep in d.reports.filter { it.kind == ReportKind.CAPTURE && !it.synced }) {
                    when (val r = Workspace.capture(notion, cfg.inboxDb, rep.text, rep.tag, d.date)) {
                        is ApiResult.Ok -> {
                            ok++
                            store.updateDay(d.date) { DayOps.markCaptureSynced(it, rep.at) }
                        }
                        is ApiResult.Err -> failed = r.message
                    }
                    if (failed != null) break
                }
            }
            if (failed != null) break
        }
        store.syncNote = failed?.let { "上次同步失败：$it" } ?: "上次同步成功 ${DayClock.hhmm(minuteNow())}"
        failed?.let { "同步失败：$it" } ?: if (ok == 0) "没有要同步的" else "已同步 $ok 条"
    }

    private fun minuteNow(): Int = Instant.now().atZone(store.zone).toLocalTime().let { it.hour * 60 + it.minute }

    // ---------- 设置页的检查 ----------

    suspend fun testAi(): String = when (val r = llm.complete("只回复 JSON。", """回复 {"pass": true, "score": 100, "feedback": "连接正常"}""")) {
        is ApiResult.Ok -> Verifier.parseVerdict(r.value)?.let { "连接正常，模型能按格式回复" }
            ?: "连上了，但回复不是 JSON：${r.value.take(60)}。可以试试开关 JSON 模式"
        is ApiResult.Err -> r.message
    }

    suspend fun testNotion(): String = when (val r = notion.me()) {
        is ApiResult.Ok -> "连接正常：${r.value}"
        is ApiResult.Err -> r.message
    }

    /** 一键搭建：在父页面下建三个库，填进设置，信息收集关卡改为 Notion 查账。 */
    suspend fun setupNotion(parentPage: String): String {
        val r = Workspace.setup(notion, parentPage)
        return when (r) {
            is ApiResult.Err -> r.message
            is ApiResult.Ok -> {
                val created = r.value
                val now = System.currentTimeMillis()
                var note = ""
                store.updateConfig { c ->
                    var next = c.copy(
                        notion = c.notion.copy(
                            parentPage = parentPage,
                            inboxDb = created.inboxDb,
                            thinkDb = created.thinkDb,
                            logDb = created.logDb,
                        ),
                    )
                    val info = next.gates.firstOrNull { it.id == "info_daily" }
                    if (info != null && info.verify == VerifyMode.TEXT) {
                        val out = Policy.putGate(next, info.copy(verify = VerifyMode.NOTION, notionDb = created.inboxDb), now)
                        next = out.config
                        note = "\n" + out.message
                    }
                    next
                }
                "已在 Notion 建好：信息收集库、谋划库、总控日志。$note"
            }
        }
    }
}
