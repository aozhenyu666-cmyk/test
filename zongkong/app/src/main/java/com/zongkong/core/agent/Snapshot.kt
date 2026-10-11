package com.zongkong.core.agent

import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.Engine
import com.zongkong.core.Status
import com.zongkong.core.VerifyMode
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.Instant
import java.time.ZoneId

/** 把总控此刻的状态写成几行字，给参谋看。系统提示里放简版，get_overview 给全版。 */
object Snapshot {

    fun clock(now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return "%d-%02d-%02d 周%s %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, "一二三四五六日"[t.dayOfWeek.value - 1], t.hour, t.minute)
    }

    private fun actionLine(a: Rec, work: Work, now: Long, zone: ZoneId): String {
        val t = work.rec(a.threadKey)
        val time = a.plan?.let { TimeParse.label(it, now, zone) } ?: "没排时间"
        return "「${a.title}」[${a.key}] ${a.actionStatus.label} · $time" +
            (t?.let { " · ${it[F.CODE]}" } ?: "") +
            (a[F.CRITERIA].takeIf { it.isNotBlank() }?.let { " · 完成依据：${it.take(50)}" } ?: "")
    }

    /** 系统提示里的简版：十来行。 */
    fun brief(env: AgentEnv): String {
        val now = env.now()
        val zone = env.zone
        val status = env.status()
        val work = env.work()
        val book = env.plans()
        val main = com.zongkong.core.plan.Plans.day(book, DayClock.dateKey(now, zone))?.mainActionKey.orEmpty()
        val cur = Focus.current(work, now, zone, main)
        return buildString {
            append("现在：${clock(now, zone)}\n")
            append("总控：${status.headline}。${Engine.summary(status)}\n")
            append("今天关卡：").append(status.gates.joinToString("；") { g -> "${g.gate.title}${if (g.done) "✓" else "（${g.phase.label}，${DayClock.hhmm(g.gate.deadline)}截止）"}" }.ifBlank { "无" }).append('\n')
            val sess = work.session
            if (sess != null) append("正在做：${work.rec(sess.actionKey)?.title}，专注锁${if (sess.lockUntil > now) "到 ${hm(sess.lockUntil, zone)}" else "没开"}\n")
            cur.action?.let { append("当前一步（${cur.why.label}）：${actionLine(it, work, now, zone)}\n") }
            val next = Focus.upcoming(work, cur.action?.key, now, zone, 4)
            if (next.isNotEmpty()) append("接下来：").append(next.joinToString("；") { actionLine(it, work, now, zone) }).append('\n')
            val attention = Focus.needsAttention(work)
            if (attention.isNotEmpty()) append("等处理：").append(attention.take(4).joinToString("；") { "「${it.title}」${it.actionStatus.label}" }).append('\n')
            val threads = work.threads().filter { it.threadStatus.open }.sortedByDescending { it.updatedAt }
            append("进行中的事（${threads.size}）：").append(threads.take(8).joinToString("；") { "${it[F.CODE]} ${it.title}（${it.threadStatus.label}）" }.ifBlank { "无" }).append('\n')
            append("今天娱乐 ${status.playMin} 分钟" + (if (status.quotaMin > 0) " / 上限 ${status.quotaMin}" else "") + "\n")
            val plan = com.zongkong.core.plan.Plans.active(book, now, zone)
            if (plan == null) {
                append("本周计划：还没有" + (if (com.zongkong.core.plan.Plans.draft(book, now, zone) != null) "（有一份草稿等他立项）" else "") + "\n")
            } else {
                append("本周计划：").append(plan.goals.joinToString("；") { g -> g.title + "（" + plan.outcomesOf(g.id).joinToString("、") { (if (it.done) "✓" else "") + it.title } + "）" }).append('\n')
                append("今天的主线：").append(work.rec(main)?.title?.let { "「$it」" } ?: "还没定").append('\n')
                cur.action?.let { a -> if (!com.zongkong.core.plan.Plans.inPlan(plan, a)) append("当前这一步不在本周计划里。\n") }
            }
        }
    }

    fun full(env: AgentEnv): String {
        val now = env.now()
        val work = env.work()
        return buildString {
            append(brief(env))
            append("\n未排时间的下一步：")
            append(Focus.unplannedThreads(work).take(6).joinToString("；") { "${it[F.CODE]} ${it.title}：${it[F.NEXT].take(40)}" }.ifBlank { "无" })
            append("\n同步：")
            append(if (work.lastSyncAt == 0L) "还没和 Notion 同步过" else "上次 ${hm(work.lastSyncAt, env.zone)}，${if (work.lastSyncOk) "成功" else "失败：" + work.lastSyncMessage}")
            append("，待同步 ${work.pendingCount} 条")
            val changes = work.changes.takeLast(5)
            if (changes.isNotEmpty()) append("\n最近的变化：").append(changes.joinToString("；") { "${it.by}${it.what}「${it.title}」" })
            com.zongkong.core.plan.Plans.active(env.plans(), now, env.zone)?.let { append("\n\n").append(com.zongkong.core.plan.Plans.describe(it, work, env.plans(), now, env.zone)) }
            append("\n首页快捷按钮：").append(env.config().shortcuts.joinToString("、") { it.label }.ifBlank { "无" })
            append("\n\n").append(gates(env.config(), env.status(), now))
        }
    }

    fun gates(config: Config, status: Status, now: Long): String = buildString {
        append("关卡：\n")
        config.gates.forEach { g ->
            val today = status.gates.firstOrNull { it.gate.id == g.id }
            val st = when {
                today == null -> "今天不生效"
                today.done -> "今天已验收"
                else -> today.phase.label
            }
            val verify = if (g.verify == VerifyMode.EVIDENCE) "看证据：${Focus.Evidence.entries.firstOrNull { it.name == g.evidence }?.label ?: g.evidence} ≥ ${g.evidenceMin}" else g.verify.label
            append("· id=${g.id}｜${g.dept.label}｜${g.title}｜${DayClock.hhmm(g.openAt)}–${DayClock.hhmm(g.deadline)}｜${g.block.label}｜$verify｜$st\n")
        }
        if (config.pending.isNotEmpty()) {
            append("待生效的放宽：\n")
            config.pending.forEach { append("· ${it.summary}（${((it.effectiveAt - now) / 3600_000).coerceAtLeast(0)} 小时后）\n") }
        }
        append("拦截名单 ${config.blocked.size} 个应用；沉默 ${config.silenceHours} 小时就严管；娱乐上限 ${config.dailyQuotaMin} 分钟/天")
    }

    fun thread(work: Work, t: Rec, zone: ZoneId): String = buildString {
        val now = System.currentTimeMillis()
        append("${t[F.CODE]}「${t.title}」${t.threadStatus.label}")
        if (t[F.PRIORITY].isNotBlank()) append(" · 优先级${t[F.PRIORITY]}")
        append('\n')
        listOf(F.WHY to "为什么", F.JUDGE to "当前判断", F.UNSURE to "不确定", F.BREAK to "断点", F.NEXT to "下一步", F.CRITERIA to "完成依据").forEach { (k, label) ->
            if (t[k].isNotBlank()) append("$label：${t[k].take(400)}\n")
        }
        val actions = work.actionsOf(t.key).sortedByDescending { it.updatedAt }
        append("行动（${actions.size}）：\n")
        actions.take(15).forEach { a ->
            append("· ${actionLine(a, work, now, zone)}")
            if (a[F.RESULT].isNotBlank()) append(" · 结果：${a[F.RESULT].take(80)}")
            if (a[F.BREAK].isNotBlank()) append(" · 断点：${a[F.BREAK].take(80)}")
            append('\n')
        }
        val notes = work.notesOf(t.key).sortedByDescending { it.createdAt }
        if (notes.isNotEmpty()) {
            append("最近记录：\n")
            notes.take(8).forEach { n -> append("· [${n.noteType.label}] ${n.title}：${n[F.RAW].take(120).replace('\n', ' ')}\n") }
        }
    }

    private fun hm(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
}
