package com.zongkong.app.data

import com.zongkong.app.link.YishouClient
import com.zongkong.core.YishouStatus

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
    val notion = NotionClient({ store.config.value.notion.token }, baseUrl = { store.config.value.notion.baseUrl })
    private val verifier = Verifier(llm, notion)
    private val syncLock = Mutex()

    /** 事项、行动、记录的操作和双向同步。 */
    val work = WorkActions(context, store, notion)

    /** 计划：草稿、立项、主线、交出物。 */
    val plan = PlanActions(store, work)

    /** 总控时间。 */
    val hq = HqActions(context, store, this)

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
        if (gs.gate.verify == VerifyMode.EVIDENCE) {
            // 自己写的判断也算一条结论记录
            if (gs.gate.evidence == com.zongkong.core.work.Focus.Evidence.CONCLUSIONS.name && TextCheck.effectiveChars(text, Defaults.fillTemplate(gs.gate.template, date)) >= 20) {
                work.addConclusion(text)
            }
            // 先拉一次 Notion：GPT 刚写进去的也要算
            if (store.config.value.notion.workReady) work.sync()
        }
        val facts = Facts.build(status, store.today.value, store.zone, com.zongkong.core.work.Focus.daySummary(store.work.value, now, store.zone))
        val yishou = if (gs.gate.evidence == com.zongkong.core.work.Focus.Evidence.YISHOU_SETS.name) YishouClient.status(context) else null
        val evidence = evidenceOf(yishou)
        return when (val out = verifier.verify(gs.gate, text, store.config.value, date, store.zone, facts, evidence)) {
            is VerifyOutcome.Done -> {
                record(gateId, text, out.verdict)
                SubmitResult.Judged(out.verdict)
            }
            is VerifyOutcome.Unavailable -> SubmitResult.Unavailable(out.reason, if (out.canDegrade) status.degradedLeft else 0)
        }
    }

    private fun evidenceOf(yishou: YishouClient.Result<YishouStatus>?): (String) -> Pair<Int, List<String>> = { k ->
        val kind = com.zongkong.core.work.Focus.Evidence.entries.firstOrNull { it.name == k } ?: com.zongkong.core.work.Focus.Evidence.PLANNED_TODAY
        if (kind == com.zongkong.core.work.Focus.Evidence.WEEK_PLAN) {
            val now = System.currentTimeMillis()
            val p = com.zongkong.core.plan.Plans.active(store.plans.value, now, store.zone)
                ?: com.zongkong.core.plan.Plans.nextActive(store.plans.value, now, store.zone)
            if (p == null) 0 to emptyList() else 1 to listOf(p.goals.joinToString("、") { it.title })
        } else if (kind == com.zongkong.core.work.Focus.Evidence.YISHOU_SETS) {
            when (yishou) {
                is YishouClient.Result.Ok -> yishou.value.doneSets to listOf(yishou.value.summary())
                is YishouClient.Result.Err -> 0 to listOf(yishou.message)
                null -> 0 to emptyList()
            }
        } else {
            com.zongkong.core.work.Focus.evidence(store.work.value, kind, System.currentTimeMillis(), store.zone)
        }
    }

    /**
     * 看证据的关卡：证据够了就自动打勾，不用你去点。没够不记失败（不算一次提交）。
     * 首页打开、后台每隔几分钟各查一次。返回自动通过的关卡标题。
     */
    suspend fun autoVerify(): List<String> {
        val now = System.currentTimeMillis()
        val status = store.status(now)
        val open = status.gates.filter { !it.done && it.phase != GatePhase.NOT_OPEN && it.gate.verify == VerifyMode.EVIDENCE }
        if (open.isEmpty()) return emptyList()
        val needYishou = open.any { it.gate.evidence == com.zongkong.core.work.Focus.Evidence.YISHOU_SETS.name }
        val yishou = if (needYishou && YishouClient.installed(context)) YishouClient.status(context) else null
        val evidence = evidenceOf(yishou)
        val passed = mutableListOf<String>()
        for (gs in open) {
            val (n, titles) = evidence(gs.gate.evidence)
            if (n < gs.gate.evidenceMin) continue
            val kind = com.zongkong.core.work.Focus.Evidence.entries.firstOrNull { it.name == gs.gate.evidence } ?: continue
            val list = if (titles.isEmpty()) "" else "：" + titles.joinToString("、") { "「$it」" }
            record(gs.gate.id, "（自动验收）", Verdict(true, "事项证据", "${kind.label} $n 条$list"))
            passed += gs.gate.title
        }
        return passed
    }

    /**
     * 一手每晚总结出的规则，记进总控（挂到一手正在练的那件事上），这样也会同步到 Notion，GPT 能看到。
     * 每个日期只记一次。返回记下的规则，没有新的返回 null。
     */
    suspend fun absorbYishou(): String? {
        if (!YishouClient.installed(context)) return null
        val y = (YishouClient.status(context) as? YishouClient.Result.Ok)?.value ?: return null
        if (y.rule.isBlank() || y.summaryDate.isBlank() || y.summaryDate == store.yishouSummaryDate) return null
        val threadKey = store.work.value.rec(y.actionKey)?.threadKey.orEmpty()
        val text = buildString {
            append("一手 ${y.summaryDate} 的每晚总结\n")
            append("规则：${y.rule}\n")
            if (y.tomorrowQuestion.isNotBlank()) append("明天第一问：${y.tomorrowQuestion}\n")
            if (y.summaryNote.isNotBlank()) append("推进到：${y.summaryNote}\n")
            if (y.taskTitle.isNotBlank()) append("在练：${y.taskTitle}")
        }
        work.addConclusion(text, threadKey)
        store.yishouSummaryDate = y.summaryDate
        return y.rule
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
        if (text != "（自动验收）") store.touch()
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
        store.touch()
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
        // 总控者的态度是“严格”时不给紧急放行（信用低于 40，或者总控自己收紧了）
        if (store.stance(now) == com.zongkong.core.boss.Stance.STRICT) {
            return "总控现在的态度是“严格”（信用 ${store.credibility(now)}），不给紧急放行。去跟总控说清楚，或者把约定做到。"
        }
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
    suspend fun sync(): String {
        // 设好了事项/行动/记录三个库：验收、报到都作为“记录”走双向同步
        if (store.config.value.notion.workReady) {
            work.absorbOutbox()
            return work.sync()
        }
        return syncLock.withLock { syncLocked() }
    }

    private suspend fun syncLocked(): String {
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
        return failed?.let { "同步失败：$it" } ?: if (ok == 0) "没有要同步的" else "已同步 $ok 条"
    }

    private fun minuteNow(): Int = Instant.now().atZone(store.zone).toLocalTime().let { it.hour * 60 + it.minute }

    // ---------- 设置页的检查 ----------

    suspend fun testAi(): String = when (val r = llm.complete("只回复 JSON。", """回复 {"pass": true, "score": 100, "feedback": "连接正常"}""")) {
        is ApiResult.Ok -> Verifier.parseVerdict(r.value)?.let { "连接正常，模型能按格式回复" }
            ?: "连上了，但回复不是 JSON：${r.value.take(60)}。可以试试开关 JSON 模式"
        is ApiResult.Err -> r.message
    }

    /** 现在的 AI 接口能不能用（模型锁判断要不要 24 小时）。 */
    suspend fun aiWorks(): Boolean = store.config.value.ai.ready && llm.complete("只回复 JSON。", """回复 {"pass": true}""") is ApiResult.Ok

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
