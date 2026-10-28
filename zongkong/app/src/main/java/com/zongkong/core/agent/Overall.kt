package com.zongkong.core.agent

import com.zongkong.core.DayClock
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import java.time.Instant
import java.time.ZoneId

/**
 * 「整体」快照：一眼看清长期局面。
 *
 * 和 [Snapshot.brief]（当下：今天几点、现在推哪一步）不同，这里只回答几件事：
 * 长期目标走到哪了、这周要交出什么、哪些判断还没验证、哪里受阻或偏离、一手和电脑最近报了什么、下一步该判断什么。
 *
 * 三条硬规矩：
 * 1. 每一条都从当前有效的数据算出来，或者写明来源和时间；缺数据就写「未记录 / 未知」，不猜。
 * 2. 「连接成功」不等于「练完了」，「命令已发出」不等于「电脑照做了」——只按回执和结果说话。
 * 3. 一份快照给一条用户消息用到底：同一次工具循环里不重读外部状态，消息开头读一次就好。
 */
object OverallSnapshot {

    /** 外部状态（一手 / 电脑）多久算新鲜；超过就在快照里标「数据过期」。 */
    const val FRESH_MS = 30 * 60_000L

    /** 最多列几个长期目标 / 事项 / 假设，避免把快照写成数据库。 */
    const val MAX_AIMS = 4
    const val MAX_THREADS = 6
    const val MAX_HYPO = 5

    /**
     * 一手（训练 App）的读数。[readAt] 是这次去读的时刻；[error] 非空表示这次没读到。
     * 没读到的时候绝不沿用旧结果冒充现在。
     */
    data class External(
        val source: String,
        val text: String = "",
        val readAt: Long = 0,
        val error: String = "",
    ) {
        val blank: Boolean get() = text.isBlank() && error.isBlank()
        val ok: Boolean get() = error.isBlank() && text.isNotBlank()
    }

    /**
     * 生成整体快照。[yishou] 由调用方在读用户消息的那一刻取一次（一手是异步读取，不能塞进这个同步方法），
     * 同一条用户消息的后续工具循环直接复用这份字符串。
     */
    fun build(env: AgentEnv, yishou: External? = null): String {
        val now = env.now()
        val zone = env.zone
        val work = env.work()
        val book = env.plans()
        val today = DayClock.dateKey(now, zone)
        return buildString {
            append(header(now, zone))
            append("\n## 长期目标\n")
            append(aims(env, work, now, zone))
            append("\n## 本周交付\n")
            append(week(env, work, now, zone))
            append("\n## 当前主线与下一步\n")
            append(mainline(work, book, now, zone))
            append("\n## 关键事项\n")
            append(keyThreads(work, now))
            append("\n## 待验证的判断与假设\n")
            append(hypotheses(env))
            append("\n## 外接谋划单\n")
            append(directives(env))
            append("\n## 执行结果（一手 / 电脑）\n")
            append(execution(env, work, yishou, now, zone))
            append("\n## 偏离与阻塞\n")
            append(drift(work, book, now, zone, today))
            append("\n## 下一步候选（还没排时间的）\n")
            append(candidates(work))
        }.trimEnd()
    }

    // ---------- 各段 ----------

    private fun header(now: Long, zone: ZoneId): String = buildString {
        append("## 整体状态\n")
        append("生成时间：").append(Snapshot.clock(now, zone)).append("（逻辑日 ").append(DayClock.dateKey(now, zone)).append("）\n")
        append("这份快照是在这条消息开始时读的当前状态，同一轮里不会变；下一条消息会重新读。\n")
    }

    private fun aims(env: AgentEnv, work: Work, now: Long, zone: ZoneId): String {
        val list = com.zongkong.core.plan.Aims.open(env.plans())
        if (list.isEmpty()) return "未记录：还没有长期目标（求职、考试这类跨好几周的事）。用户提到时用 set_aim 挂上，不要自己编一个。\n"
        val today = DayClock.dateKey(now, zone)
        return buildString {
            list.take(MAX_AIMS).forEach { a ->
                val left = com.zongkong.core.plan.Aims.daysLeft(a, now, zone)
                append("· [").append(a.id).append("] ").append(a.title).append("（")
                append(
                    when {
                        left == null -> "没有期限"
                        left >= 0 -> "还剩 " + left + " 天"
                        else -> "已过期 " + (-left) + " 天"
                    },
                ).append("）")
                if (a.why.isNotBlank()) append("｜为什么：").append(a.why.take(40))
                append("｜近 7 天 ").append(com.zongkong.core.plan.Aims.recentSteps(a, work, now)).append(" 步")
                if (a.milestones.isEmpty()) {
                    append("｜里程碑：未记录")
                } else {
                    append("｜里程碑 ").append(a.milestones.count { it.done }).append("/").append(a.milestones.size)
                    com.zongkong.core.plan.Aims.nextMilestone(a)?.let { m ->
                        append("，下一个：").append(m.title).append("（").append(m.due.ifBlank { "没日期" }).append("）")
                    }
                    val late = a.milestones.filter { !it.done && it.due.isNotBlank() && it.due < today }
                    if (late.isNotEmpty()) append("；过期没做：").append(late.joinToString("、") { it.title })
                }
                append('\n')
            }
            val idle = list.filter { it.threads.isNotEmpty() && com.zongkong.core.plan.Aims.recentSteps(it, work, now) == 0 }
            if (idle.isNotEmpty()) append("一周没有一步落在：").append(idle.joinToString("、") { it.title }).append("（这是事实，不猜原因）\n")
        }
    }

    private fun week(env: AgentEnv, work: Work, now: Long, zone: ZoneId): String {
        val book = env.plans()
        val plan = com.zongkong.core.plan.Plans.active(book, now, zone)
        if (plan == null) {
            val draft = com.zongkong.core.plan.Plans.draft(book, now, zone)
            return if (draft == null) "未记录：这一周还没有立项的计划。\n"
            else "未立项：有一份草稿（" + draft.goals.joinToString("、") { it.title } + "），等用户点立项。\n"
        }
        val pr = com.zongkong.core.plan.Plans.progress(plan, work, now, zone)
        val ws = java.time.LocalDate.parse(plan.weekStart)
        return buildString {
            append("本周计划（").append(ws.monthValue).append('.').append(ws.dayOfMonth).append(" 起，第 ").append(pr.day).append(" 天）：\n")
            plan.goals.forEach { g ->
                val gp = pr.goals.firstOrNull { it.goal.id == g.id }
                append("· ").append(g.title)
                if (gp != null) append("：交出 ").append(gp.outcomesDone).append('/').append(gp.outcomes)
                    .append("，做完 ").append(gp.actionsDone).append(" 步，").append(gp.minutes).append(" 分钟")
                append('\n')
                plan.outcomesOf(g.id).forEach { o ->
                    append("    - ").append(if (o.done) "✓ " else "○ ").append(o.title)
                    if (o.criteria.isNotBlank()) append("（做到：").append(o.criteria.take(40)).append("）")
                    append('\n')
                }
            }
            append("时间：计划内 ").append(pr.planMinutes).append(" 分钟 / 计划外 ").append(pr.offMinutes)
            append(" 分钟；今天计划内 ").append(pr.todayPlanMinutes).append('/').append(plan.dailyMinutes).append(" 分钟\n")
            if (plan.revisions.isNotEmpty()) append("改过 ").append(plan.revisions.size).append(" 次，最近：")
            if (plan.revisions.isNotEmpty()) append(plan.revisions.last().what).append("（").append(plan.revisions.last().why).append("）\n")
        }
    }

    private fun mainline(work: Work, book: com.zongkong.core.plan.PlanBook, now: Long, zone: ZoneId): String {
        val day = com.zongkong.core.plan.Plans.day(book, DayClock.dateKey(now, zone))
        val main = com.zongkong.core.plan.Plans.mainAction(book, work, now, zone)
        return buildString {
            if (day == null && main == null) {
                append("今天的主线：未记录（没定主线）。\n")
            } else {
                append("今天的主线：").append(main?.let { "「" + it.title + "」[" + it.key + "]" } ?: "定过，但对应的行动已经不在待做里")
                append('\n')
                main?.let { a ->
                    if (a[F.CRITERIA].isNotBlank()) append("做到什么算完：").append(a[F.CRITERIA].take(80)).append('\n')
                    a.plan?.let { p -> TimeParse.startMillis(p, zone)?.let { s -> append("排在：").append(Snapshot.clock(s, zone)).append('\n') } }
                    append("所在事项：").append(work.rec(a.threadKey)?.title ?: "未记录").append('\n')
                }
            }
            val cur = Focus.current(work, now, zone, main?.key.orEmpty())
            cur.action?.let { a ->
                if (a.key != main?.key) append("此刻在做：").append(a.title).append("（").append(cur.why.label).append("）\n")
            }
            append("为什么排它在前：").append(whyFirst(work, book, main, now, zone)).append('\n')
        }
    }

    /** 只从已有记录里找“为什么是这个”的依据；没有记录就直说没有。 */
    private fun whyFirst(work: Work, book: com.zongkong.core.plan.PlanBook, main: Rec?, now: Long, zone: ZoneId): String {
        main ?: return "未记录（没有主线，也就无法说明为什么）"
        val plan = com.zongkong.core.plan.Plans.active(book, now, zone)
        val inPlan = plan != null && com.zongkong.core.plan.Plans.inPlan(plan, main)
        val outcome = plan?.outcomes?.firstOrNull { it.actionKey == main.key || (it.threadKey.isNotBlank() && it.threadKey == main.threadKey) }
        val parts = mutableListOf<String>()
        if (outcome != null) parts += "它是本周交付「" + outcome.title + "」的下一步"
        else if (inPlan) parts += "它在本周计划里"
        else parts += "没有记录说明它为什么在前（也不在本周计划里）"
        work.rec(main.threadKey)?.let { t -> if (t[F.PRIORITY].isNotBlank()) parts += "所在事项优先级" + t[F.PRIORITY] }
        return parts.joinToString("；")
    }

    private fun keyThreads(work: Work, now: Long): String {
        val threads = work.threads().filter { it.threadStatus.open }
            .sortedWith(compareByDescending<Rec> { it[F.PRIORITY] == "高" }.thenByDescending { it.updatedAt })
        if (threads.isEmpty()) return "未记录：没有进行中的事项。\n"
        return buildString {
            threads.take(MAX_THREADS).forEach { t ->
                append("· ").append(t[F.CODE].ifBlank { "无编号" }).append(' ').append(t.title)
                append("（").append(t.threadStatus.label).append("）")
                if (t[F.JUDGE].isNotBlank()) append("｜判断：").append(t[F.JUDGE].take(60))
                if (t[F.UNSURE].isNotBlank()) append("｜未知：").append(t[F.UNSURE].take(60))
                if (t[F.BREAK].isNotBlank()) append("｜断点：").append(t[F.BREAK].take(40))
                if (t[F.NEXT].isNotBlank()) append("｜下一步：").append(t[F.NEXT].take(50))
                if (t[F.CRITERIA].isNotBlank()) append("｜算完：").append(t[F.CRITERIA].take(40))
                append("｜开着 ").append(work.actionsOf(t.key).count { it.actionStatus.open }).append(" 步")
                append('\n')
            }
            if (threads.size > MAX_THREADS) append("（还有 ").append(threads.size - MAX_THREADS).append(" 件事没列，需要时用 list_threads 看）\n")
        }
    }

    private fun hypotheses(env: AgentEnv): String {
        val vein = env.vein()
        val hypo = vein.nodes.filter { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
        if (hypo.isEmpty()) {
            return if (vein.nodes.isEmpty()) "未记录：脉络里还没有节点。\n"
            else "没有待验证的假设（" + vein.nodes.size + " 个节点都已有结论）。\n"
        }
        return buildString {
            hypo.sortedByDescending { it.updated }.take(MAX_HYPO).forEach { n ->
                append("· [").append(n.id).append("] ").append(n.title)
                append("｜假设：").append((n.claim.ifBlank { n.title }).take(50))
                append("｜来源：").append(n.source.ifBlank { "未记录" })
                append("｜验证：").append(n.verify.ifBlank { "没写验证方式" })
                append("｜更新：").append(n.updated.ifBlank { "未记录" })
                append('\n')
            }
            if (hypo.size > MAX_HYPO) append("（还有 ").append(hypo.size - MAX_HYPO).append(" 个假设，用 vein_due 看）\n")
            append("这些都是「待验证」，不是事实。\n")
        }
    }

    private fun directives(env: AgentEnv): String {
        val list = env.directives()
        val active = list.filter { it.status == com.zongkong.core.plan.Directive.STATUS_ACTIVE }
        if (active.isEmpty()) return "未记录：没有在办的外接谋划单。\n"
        val head = com.zongkong.core.plan.Directives.tally(list)
        return (if (head.isBlank()) "" else head + "\n") + com.zongkong.core.plan.Directives.brief(list)
    }

    private fun execution(env: AgentEnv, work: Work, yishou: External?, now: Long, zone: ZoneId): String = buildString {
        // 一手：只认这一轮读到的；读不到就说读不到，不拿旧结果冒充现在
        append("· 一手训练：")
        if (yishou == null || yishou.blank) {
            append("这一轮没有读（需要时现读；读不到就说读不到）\n")
        } else if (!yishou.ok) {
            append("读取失败：").append(yishou.error.take(80)).append("（").append(hm(yishou.readAt, zone)).append(" 尝试）\n")
        } else {
            append('\n')
            yishou.text.trim().lines().forEach { l -> if (l.isNotBlank()) append("  ").append(l.trim()).append('\n') }
            append("  读取时间：").append(hm(yishou.readAt, zone))
            if (now - yishou.readAt > FRESH_MS) append("（已超过 ").append(FRESH_MS / 60_000).append(" 分钟，可能过期）")
            append('\n')
            append("  注意：「连上了」不等于「练完了」，只按上面的训练结果说话。\n")
        }
        // 电脑
        append("· 电脑执行层：")
        val pc = env.pcSide()
        if (pc == null) {
            append("未接入 / 当前读不到（不要假装有电脑的监控数据）\n")
        } else {
            append('\n').append(com.zongkong.core.device.DeviceLink.brief(pc, now, zone))
            if (pc.at > 0 && now - pc.at > FRESH_MS) {
                append("  数据过期：最后一次状态是 ").append(hm(pc.at, zone)).append("，已超过 ").append(FRESH_MS / 60_000).append(" 分钟\n")
            }
            append("  注意：「命令已发出」不等于「电脑照做了」，以电脑回写的回执和时间为准。\n")
        }
        // 本机最近的结果记录
        val results = work.notes().filter { it.noteType == com.zongkong.core.work.NoteType.RESULT }.sortedByDescending { it.createdAt }
        append("· 最近的行动结果：")
        if (results.isEmpty()) {
            append("未记录\n")
        } else {
            append('\n')
            results.take(4).forEach { n ->
                append("  - ").append(Snapshot.clock(n.createdAt, zone)).append(' ').append(n.title)
                if (n[F.RAW].isNotBlank()) append("：").append(n[F.RAW].replace('\n', ' ').take(80))
                append('\n')
            }
        }
    }

    private fun drift(work: Work, book: com.zongkong.core.plan.PlanBook, now: Long, zone: ZoneId, today: String): String = buildString {
        val late = work.actions().filter { a ->
            a.actionStatus.open && a.plan?.let { p ->
                TimeParse.startMillis(p, zone)?.let { DayClock.dateKey(it, zone) < today } == true
            } == true
        }
        append("· 排过时间、日期已过还没做的：")
        if (late.isEmpty()) {
            append("没有\n")
        } else {
            append('\n')
            late.take(5).forEach { a ->
                append("  - [").append(a.key).append("] ").append(a.title).append("｜").append(a.actionStatus.label).append('\n')
            }
        }
        val stuck = work.actions().filter { it.actionStatus == ActionStatus.STUCK }
        append("· 标了卡住的行动：")
        if (stuck.isEmpty()) {
            append("没有\n")
        } else {
            append('\n')
            stuck.take(5).forEach { a ->
                append("  - [").append(a.key).append("] ").append(a.title)
                val reason = a[F.STUCK].ifBlank { a[F.BREAK] }
                if (reason.isNotBlank()) append("｜原因：").append(reason.take(40))
                append('\n')
            }
        }
        val stuckThreads = work.threads().filter { it.threadStatus == com.zongkong.core.work.ThreadStatus.STUCK }
        append("· 卡住的事项：")
        if (stuckThreads.isEmpty()) append("没有\n") else append(stuckThreads.joinToString("、") { it.title }).append('\n')
        val plan = com.zongkong.core.plan.Plans.active(book, now, zone)
        append("· 本周计划外的投入：")
        if (plan == null) {
            append("未记录（这周没有立项的计划，谈不上偏离）\n")
        } else {
            val pr = com.zongkong.core.plan.Plans.progress(plan, work, now, zone)
            append(pr.offMinutes).append(" 分钟")
            if (pr.offDone.isNotEmpty()) append("，做完的计划外：").append(pr.offDone.take(5).joinToString("、"))
            append('\n')
        }
        append("只列记录里有的差异；没有证据的地方（比如“为什么没做”）写未知，不猜动机。\n")
    }

    private fun candidates(work: Work): String {
        val unplanned = Focus.unplannedThreads(work)
        if (unplanned.isEmpty()) return "没有排时间的下一步：无\n"
        return buildString {
            unplanned.take(6).forEach { t ->
                append("· [").append(t[F.CODE].ifBlank { t.key }).append("] ").append(t.title)
                if (t[F.NEXT].isNotBlank()) append("：").append(t[F.NEXT].take(50))
                append('\n')
            }
            append("这些都还没排进时间。不要一次全变成待办：只有能推动目标、能验收、值得占资源的一步才建议进行动层。\n")
        }
    }

    // ---------- 全局页区块 → 整体对话的主题 ----------

    /** 全局页每个区块跳进「整体」对话时带过去的主题（稳定的键值，界面层再转成可读提示）。 */
    enum class Topic(val key: String, val label: String, val chip: String, val prompt: String) {
        LONG_TERM_GOALS(
            "long_term_goals", "长期目标", "聊聊长期目标",
            "我们从长期目标说起：现在挂着几个、各自走到哪了、下一个里程碑是什么、哪些其实已经不该占着位置。",
        ),
        WEEKLY_OUTCOMES(
            "weekly_outcomes", "本周交付", "讨论本周交付",
            "看这周的交付：本周计划要交出什么、现在到什么程度、有没有假进度（写了但没有证据）。缺的补上，虚的改掉。",
        ),
        BLOCKERS(
            "blockers", "受阻与偏离", "分析当前阻塞",
            "把受阻和偏离的地方捋一遍：哪些行动卡住了、哪些排了时间没做、这周计划外花了多少时间。只按记录说，不猜原因。",
        ),
        HYPOTHESES(
            "hypotheses", "待验证判断", "检查待验证判断",
            "看脉络里待验证的判断：哪几个现在最影响决定、各自怎么验证最便宜、先验证哪个。待验证的不要说成事实。",
        ),
        EXECUTION_FEEDBACK(
            "execution_feedback", "执行反馈", "复盘一手 / 设备结果",
            "复盘一手和电脑最近的执行结果：读到了什么、哪些读不到或过期、这些结果改变了哪个判断。",
        ),
        ;

        companion object {
            fun of(key: String): Topic? = entries.firstOrNull { it.key == key.trim() }
        }
    }

    private fun hm(t: Long, zone: ZoneId): String =
        if (t <= 0) "时间不明" else Instant.ofEpochMilli(t).atZone(zone).let { String.format("%02d:%02d", it.hour, it.minute) }
}
