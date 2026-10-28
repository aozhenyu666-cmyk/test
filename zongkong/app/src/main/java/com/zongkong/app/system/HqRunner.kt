package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqTrigger
import com.zongkong.core.plan.Playbook
import com.zongkong.core.plan.Plans
import java.time.Instant

/**
 * 总控主动找你：
 *  - 每日总控时间：提前 10 分钟轻震提醒；到点全屏 + 连续震动；5 分钟还没来，自动进入（其他应用一打开就回总控）
 *  - 参谋定时发消息：早上第一条；午后主线还没开始问一句
 *  - 失联阶梯：一天没交流，参谋主动找你 + 重震；两天严管；三天下次打开别的应用直接进总控时间（在守护服务里）
 *
 * 守护服务每 20 秒调一次，系统闹钟兜底。每件事每天只做一次。
 */
object HqRunner {
    private const val TAG = "HqRunner"

    fun check(context: Context) {
        try {
            run(context)
        } catch (e: Exception) {
            Log.w(TAG, "检查出错", e)
        }
    }

    private fun run(context: Context) {
        val app = context.zk
        val store = app.store
        val config = store.config.value
        val cfg = config.hq
        val now = System.currentTimeMillis()
        val zone = store.zone
        if (config.pausedUntil > now) return

        // 进去了没封存、超时一小时：自动收掉，别把手机锁一整夜
        store.hq.value.active?.let { s ->
            if (now > s.endsAt + 60 * 60_000L) app.actions.hq.exitEarly("超时一小时没封存，自动结束", systemEnding = true)
        }

        // 和电脑同步（总控时间开始 / 结束时马上同步，电脑跟着锁 / 解）
        app.actions.device.maybeSync()

        val due = Hq.due(cfg, store.hq.value.sessions, now, zone)
        // v2：眼睛在不在，看真实健康状态（真实 ack），不看进程里的 running 布尔。
        val eyes = runCatching {
            com.zongkong.app.healthwire.PermissionStatusBridge.status(context) ==
                com.zongkong.app.health.HealthStatus.HEALTHY
        }.getOrDefault(false)
        when (due) {
            Hq.Due.SOON -> if (store.firstAlert("hq-soon")) {
                Notifications.alert(context, "10 分钟后是总控时间", "${DayClock.hhmm(cfg.at)} 开始，${cfg.minutes} 分钟。手上的事收个尾。", "hq")
                Buzz.once(context, Buzz.Level.GENTLE)
                store.logWake("提前 10 分钟提醒已发" + diag(context))
            }
            Hq.Due.NOW -> if (store.firstAlert("hq-now")) {
                Notifications.hqCall(context, "总控时间到了", "接下来 ${cfg.minutes} 分钟，把四个部门修一遍。" + if (cfg.autoLock) "5 分钟后自动进入。" else "", fullScreen = true)
                Buzz.relentless(context, "总控时间到了")
                if (cfg.speak) Speaker.speak(context, "总控时间到了。")
                store.logWake("到点：全屏通知 + 连续震动" + diag(context))
            }
            Hq.Due.OVERDUE -> if (cfg.autoLock && store.firstAlert("hq-auto")) {
                app.actions.hq.start(HqTrigger.SCHEDULED)
                Notifications.hqCall(context, "总控时间已经开始", "除了总控和白名单里的应用，其他的打开就回到这里。做完就解锁。", fullScreen = true)
                Buzz.relentless(context, "总控时间已经开始")
                store.logWake("自动进入总控时间" + diag(context))
            }
            Hq.Due.NONE -> {}
        }
        // 到点了但总控的眼睛（无障碍服务）没在运行：锁不住手机，单独大声说
        if (due != Hq.Due.NONE && due != Hq.Due.SOON && !eyes && store.firstAlert("hq-blind")) {
            Notifications.hqCall(
                context, "总控被关掉了，锁不住手机",
                "无障碍服务没在运行（系统清理后台或更新后会关掉它）。点这里去重新打开，然后进总控时间。",
                fullScreen = true, route = "settings/perm",
            )
            store.logWake("到点时无障碍服务没在运行：只能提醒，锁不住")
        }

        val minute = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }

        // 失联阶梯（第三级在守护服务里：打开别的应用直接进总控时间）
        val absence = store.absence(now)
        if (absence >= Hq.Absence.NUDGE && minute >= 10 * 60 && store.firstAlert("absent")) {
            val days = ((now - store.lastContact) / 86_400_000L).toInt()
            app.chat.proactive(
                "absent",
                "[定时·失联] 他已经 $days 天没和你交流了。用一两句话把他拉回来：不责备，不说教，问一个具体的、马上能回答的问题（关于今天的主线或手上的事），最后给【选项】。不要改任何东西。",
                fallback = "已经 $days 天没说话了。今天手上最要紧的是哪件？回一句就行。",
            )
            Buzz.once(context, Buzz.Level.FIRM)
        }

        boss(context, now, minute)
        playbook(context, now)

        if (!cfg.proactive) return

        // 早上第一条
        if (minute in cfg.morningAt until cfg.morningAt + 180 && store.firstAlert("morning")) {
            app.chat.proactive(
                "morning",
                "[定时·早上] 早上了，给他发今天的第一条消息。今天的主线没定的话，你直接定：按本周计划要交出的、长期目标的下一个里程碑，挑一步具体的行动 set_day_main（没有合适的行动就 add_action 建一步再定），告诉他你定了什么、为什么，他可以改。" +
                    "再提醒一个他最可能卡住的地方（看计划里的预演、昨天的电脑和手机娱乐）。最多三句，最后给【选项】。",
                fallback = "早上好。今天的主线定了吗？没定的话，打开总控挑一件。",
            )
        }

        auto(context, now, minute)

        // 午后：主线还没开始
        val plan = Plans.active(store.plans.value, now, zone)
        val main = Plans.mainAction(store.plans.value, store.work.value, now, zone)
        if (plan != null && main != null && minute in 13 * 60 + 30 until 17 * 60 && !Plans.isRestDay(plan, now, zone) &&
            Plans.progress(plan, store.work.value, now, zone).todayPlanMinutes == 0 && store.firstAlert("noon-main")
        ) {
            app.chat.proactive(
                "noon",
                "[定时·午后] 下午了，今天的主线「${main.title}」还一分钟没动。问他卡在哪，给【选项】：现在开始｜拆成 5 分钟的一步｜改到晚上。最多两句。",
                fallback = "下午了，「${main.title}」还没开始。现在开始，还是拆一步？",
            )
            Buzz.once(context, Buzz.Level.FIRM)
        }
    }

    /**
     * 预案（试验）：定稿后每段四个节点出声——开始前 5 分钟、开始、过半速记、结束交结果。
     * 通知点开就是预案页；开着“主动找你时开口说”就念出来（睡觉时段不念）。
     */
    private fun playbook(context: Context, now: Long) {
        val app = context.zk
        val store = app.store
        val script = Playbook.today(store.plans.value, now, store.zone) ?: return
        val prompts = Playbook.due(script, now, store.zone)
        if (prompts.isEmpty()) return
        val config = store.config.value
        val loud = config.voice.speakProactive && !Hq.quiet(config.hq, now, store.zone)
        prompts.forEach { p ->
            app.actions.plan.scriptPrompted(p.block.id, p.node)
            Notifications.alert(context, "预案 · ${p.node.label} · ${p.block.span}", p.text, "playbook")
            if (p.node == Playbook.Node.START || p.node == Playbook.Node.END) Buzz.once(context, Buzz.Level.FIRM) else Buzz.once(context, Buzz.Level.GENTLE)
            if (loud) Voice.speak(context, p.text)
        }
    }

    /**
     * 三个部门的自动化（要接 AI）：
     *  信息收集：晚上收集箱积压 ≥ 3 条，总控先替你提炼最多 5 条、归位，拿不准的问你。
     *  谋划思考：周六上午挑一个最该验证的假设，设计 30 分钟的验证，排进时间。
     *  统筹规划：周日傍晚下周还没计划，提议开一场大统筹。
     */
    private fun auto(context: Context, now: Long, minute: Int) {
        val app = context.zk
        val store = app.store
        if (!store.config.value.ai.ready) return
        val date = DayClock.date(now, store.zone)
        val intake = com.zongkong.core.info.Intake.stats(store.work.value, store.intake.value, now, store.zone)
        if (minute in 21 * 60 until 22 * 60 + 30 && intake.backlog >= 3 && store.firstAlert("auto-intake")) {
            app.chat.proactive(
                "intake",
                "[自动·信息收集] 收集箱积压 ${intake.backlog} 条。用 intake_status 看，按你的判断直接 distill 最多 5 条（一句话收获 + 归位到事项）；没用的建议丢；拿不准的列出来问他。" +
                    "做完用两三句告诉他你处理了什么、剩几条，最后【选项】都对｜我看看｜开一场整理收集箱。",
                fallback = "收集箱积压 ${intake.backlog} 条了。今晚总控时间里清一下。",
            )
            return
        }
        // 新建的事还很薄：晚上提议立项（一件一天只提一次）
        val thin = com.zongkong.core.work.Focus.thinThreads(store.work.value, now).filter { now - it.createdAt > 2 * 3600_000L }.maxByOrNull { it.createdAt }
        if (thin != null && minute in 19 * 60 until 21 * 60 && store.firstAlert("auto-project:${thin.key}")) {
            app.chat.proactive(
                "project",
                "[自动·立项] 他建了「${thin.title}」（${thin[com.zongkong.core.work.F.CODE]}），但还很薄：为什么、做到什么算完都没写，行动 ${store.work.value.actionsOf(thin.key).size} 步。" +
                    "用一两句点出最该补的一处（比如关键的时间节点还没查清），问他要不要现在花 15 分钟立项。最后【选项】现在立项｜明天再说｜不用。他说现在立项就 start_campaign 立项。",
                fallback = "「${thin.title}」还只有一个标题。花 15 分钟让总控帮你展开？",
            )
            return
        }
        if (date.dayOfWeek == java.time.DayOfWeek.SATURDAY && minute in 10 * 60 until 12 * 60 && store.firstAlert("auto-think")) {
            app.chat.proactive(
                "think",
                "[自动·谋划思考] 周六的谋划：看 vein_due，挑一个最该验证的假设，设计一个 30 分钟内能做完的验证，用 add_action 排进今天或明天精力最好的钟头（看 calibration），告诉他为什么挑这个。" +
                    "脉络里没有假设，就看卡住的事项和长期目标，提一个需要想清楚的问题，建议开一场大谋划。两三句，最后【选项】好｜换一个｜开一场大谋划。",
                fallback = "周六了：脉络里有一个假设该验证了，打开谋划思考部看看。",
            )
            return
        }
        val book = store.plans.value
        if (date.dayOfWeek == java.time.DayOfWeek.SUNDAY && minute in 17 * 60 until 20 * 60 &&
            Plans.nextActive(book, now, store.zone) == null && store.firstAlert("auto-plan")
        ) {
            app.chat.proactive(
                "plan",
                "[自动·统筹规划] 下周还没有计划。用两句话说清楚为什么现在要做一次大统筹（长期目标的下一个里程碑、这周实际的偏差，看 get_plan 和档案），" +
                    "最后【选项】开始大统筹｜晚上总控时间里做｜这周不做。他选“开始大统筹”就调 start_campaign。",
                fallback = "下周还没有计划。打开统筹规划部，开一场大统筹。",
            )
            return
        }
        // 外接谋划：外面交进来的事还没落实时，一天挑一条盯一下——总控是监督者，不替用户重新谋划
        val openDirs = store.config.value.directives.filter {
            it.status == com.zongkong.core.plan.Directive.STATUS_ACTIVE && it.open.isNotEmpty()
        }
        if (openDirs.isNotEmpty() && minute in 9 * 60 + 30 until 11 * 60 && store.firstAlert("auto-directive:$date")) {
            val d = openDirs.first()
            val item = d.open.first()
            app.chat.proactive(
                "directive",
                "[自动·外接谋划] 用户在 Gemini / GPT / Notion 那边交进来的「${d.title}」还有 ${d.open.size} 条没落实，比如「${item.text}」。" +
                    "先 directive_check 对账，再看 get_overview / today_report，判断这条今天能不能落一步：" +
                    "能就 add_action 排进今天，并 commit 一个到点可检查的约定；不能就说清楚卡在哪、问他要不要今天处理。" +
                    "你只监督落实，不要替用户重做这份谋划。两三句，最后【选项】今天做｜本周内｜先不管。",
                fallback = "「${d.title}」还有 ${d.open.size} 条外接谋划没落实。打开外接谋划看看。",
            )
        }
    }

    /**
     * 总控者自己的日程：到点的回访、过期没回应的约定、每天几次抽查、周日的规则复盘。
     * 一次只发一条（参谋忙着就下一轮再说）。
     */
    private fun boss(context: Context, now: Long, minute: Int) {
        val app = context.zk
        val store = app.store
        val config = store.config.value
        // 过期 24 小时没回应的约定：按没做到算，并告诉你
        val before = store.dossier.value
        val after = com.zongkong.core.boss.Boss.sweep(before, now)
        if (after != before) {
            store.updateDossier { com.zongkong.core.boss.Boss.sweep(it, now) }
            after.commitments.filter { c -> before.commitments.any { it.id == c.id && it.status != c.status } }.forEach { c ->
                Notifications.alert(context, "约定没做到", "「${c.what}」过期一天没有回应，按没做到算，信用扣 10。", "dossier")
            }
        }
        // 到点的回访
        com.zongkong.core.boss.Boss.dueChecks(store.dossier.value, now).firstOrNull()?.let { k ->
            val sent = app.chat.proactive(
                "check",
                "[定时·回访] 你之前排的回访到了：${k.about}\n先取证（device_timeline、档案、需要的话看截图），再找用户。是约定就对完证据用 resolve_commitment 下结论，结论告诉用户。",
                fallback = "回访：${k.about.take(60)}",
            )
            if (sent) {
                store.updateDossier { com.zongkong.core.boss.Boss.markDone(it, k.id) }
                Buzz.once(context, Buzz.Level.FIRM)
                return
            }
        }
        // 问候：主动开口（不是抽查——一定会说话）。标准：一天三次；黏人：每一个半小时；安静：不问
        checkIn(context, now, minute, date = DayClock.date(now, store.zone))?.let { return }
        if (!config.ai.ready) return
        // 周日的规则复盘（总控时间之前一小时）
        val date = DayClock.date(now, store.zone)
        if (date.dayOfWeek == java.time.DayOfWeek.SUNDAY && minute >= Hq.minutesOf(config.hq.at - 60) && minute < Hq.minutesOf(config.hq.at) && store.firstAlert("weekly-rules")) {
            app.chat.proactive(
                "weekly",
                "[定时·周复盘] 按宪章“预判与规则优化”做一次周复盘：get_dossier、app_usage（今天、昨天）、get_plan，看这周没做到的约定、对不上的说法、最乱的时段。" +
                    "提 1–2 条规则修改（收紧的直接用工具改，放宽的说明理由走冷静期），给下周最危险的时段排 schedule_check。最后用三四句话告诉用户你改了什么、为什么。",
                fallback = "这周复盘：去档案看看没做到的约定。",
            )
            return
        }
        // 抽查：每天几次，时间每天不一样（用户猜不到），只在检查时段里
        val n = com.zongkong.core.boss.Intensity.spotChecks(config.boss, store.stance(now)).coerceIn(0, 10)
        if (n == 0 || store.hq.value.active != null) return
        val start = config.activeStart + 60
        val end = config.activeEnd - 30
        if (end <= start) return
        val rnd = kotlin.random.Random(date.toEpochDay() * 31 + 7)
        val slots = List(n) { start + rnd.nextInt(end - start) }.sorted()
        slots.forEachIndexed { i, slot ->
            if (minute in slot until slot + 90 && store.firstAlert("spot:$i")) {
                app.chat.proactive(
                    "spot",
                    "[定时·抽查] 抽查一次。看最近 2 小时的 device_timeline（开了截图抽查就挑一两张 look_at_screenshot），对照今天的主线、未结的约定和计划。" +
                        "没问题只回复“无事”两个字。有问题：直接说你看到了什么（带数字），问一个问题或给一个要求，必要时 commit 记约定或 start_hq_session。",
                    fallback = "",
                    quiet = true,
                )
                return
            }
        }
    }

    /** 主动问候的时刻（分钟）。每天在 ±20 分钟里挪一挪，免得像闹钟。 */
    fun checkInSlots(initiative: Int, activeStart: Int, activeEnd: Int, date: java.time.LocalDate): List<Int> {
        val rnd = kotlin.random.Random(date.toEpochDay() * 17 + 3)
        val base = when (initiative) {
            0 -> emptyList()
            1 -> listOf(11 * 60, 15 * 60, 19 * 60 + 30)
            2 -> generateSequence(activeStart + 60) { it + 90 }.takeWhile { it < activeEnd - 30 }.toList()
            // 战友：每半小时
            else -> generateSequence(activeStart + 30) { it + 30 }.takeWhile { it < activeEnd - 15 }.toList()
        }
        val j = if (initiative >= com.zongkong.core.boss.Intensity.PARTNER) 5 else 20
        return base.map { (it + rnd.nextInt(-j, j + 1)).coerceIn(activeStart, activeEnd - 1) }
    }

    /**
     * 问候：挑一件具体的事问用户——今天的主线、到点的约定、待提炼的东西、该验证的假设。
     * 没配 AI 也问（固定的一句），所以“它从来不主动说话”不会再发生。返回 Unit 表示这一轮已经说过话了。
     */
    private fun checkIn(context: Context, now: Long, minute: Int, date: java.time.LocalDate): Unit? {
        val app = context.zk
        val store = app.store
        val config = store.config.value
        if (!config.hq.proactive || store.hq.value.active != null) return null
        if (Hq.quiet(config.hq, now, store.zone)) return null
        val level = com.zongkong.core.boss.Intensity.level(config.boss, store.stance(now))
        val slots = checkInSlots(level, config.activeStart, config.activeEnd, date)
        val window = if (level >= com.zongkong.core.boss.Intensity.PARTNER) 25 else 45
        val i = slots.indexOfFirst { minute in it until it + window }
        if (i < 0 || !store.firstAlert("checkin:$i")) return null
        val main = Plans.mainAction(store.plans.value, store.work.value, now, store.zone)
        val hypo = store.vein.value.nodes.filter { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
            .maxByOrNull { com.zongkong.core.vein.Veins.daysBetween(it.checks.lastOrNull()?.date ?: it.created, store.dateToday(now)) }
        val intake = com.zongkong.core.info.Intake.stats(store.work.value, store.intake.value, now, store.zone)
        // 今天还差的那一步最要紧：早上没部署就先排今天，晚上没收尾就先收尾（0.14：一天的主线在对话里走完）
        val gates = store.status(now).gates.filter { !it.done && it.phase != com.zongkong.core.GatePhase.NOT_OPEN }
        val needPlan = gates.any { it.gate.id == com.zongkong.app.data.Actions.PLAN_GATE }
        val needClose = gates.any { it.gate.id == "hq_review" } && minute >= 20 * 60
        val focusLine = when {
            needPlan -> "今天还没部署：先问他今天有哪些固定安排、想做哪几件，帮他排成具体时段，他说好就 confirm_plan。"
            needClose -> "今天还没收尾：today_report 和他过一遍，问明天第一步，close_day。"
            else -> ""
        }
        val fallback = when {
            needPlan -> "今天还没排：几点做什么？说一句，我帮你排上。"
            needClose -> "今天收个尾：做完了哪些、明天第一步是什么？"
            main != null -> "「${main.title}」现在到哪一步了？回一句就行。"
            intake.backlog > 0 -> "收集箱里有 ${intake.backlog} 条还没提炼。挑一条，用一句话说它对你有什么用。"
            hypo != null -> "脉络里「${hypo.title}」还是假设。这周验证了吗？"
            else -> "现在在做什么？接下来一小时打算做什么？"
        }
        app.chat.proactive(
            "checkin",
            "[定时·问候] 主动找用户说一句（这次一定要说，不能回“无事”）。" + focusLine +
                "没有这类事，就先看档案、今天的主线、未结的约定、intake_status、vein_due，" +
                "挑最该问的一件，问一个具体、马上能回答的问题；如果有约定快到点，就问那个。一两句，最后给【选项】。态度按档案里的来。",
            fallback = fallback,
        )
        Buzz.once(context, Buzz.Level.GENTLE)
        store.logWake("问候：第 ${i + 1} 次（强度 ${com.zongkong.core.boss.BossConfig.INITIATIVE.getOrElse(level) { "?" }}）")
        return Unit
    }

    /** 记录时附上现在的状况：眼睛有没有真实响应、通知能不能发。 */
    private fun diag(context: Context): String = buildString {
        val healthy = runCatching {
            com.zongkong.app.healthwire.PermissionStatusBridge.status(context) ==
                com.zongkong.app.health.HealthStatus.HEALTHY
        }.getOrDefault(false)
        if (!healthy) append("（无障碍没有真实响应）")
        if (!Notifications.canPost(context)) append("（通知被关了）")
    }

    /** 下一个要叫醒手机的时刻（给系统闹钟兜底）。 */
    fun nextAlarm(context: Context, now: Long): Long? {
        val app = context.zk
        val cfg = app.store.config.value.hq
        val zone = app.store.zone
        if (!cfg.enabled) return null
        val start = Hq.todayStart(cfg, now, zone)
        val morning = DayClock.at(DayClock.date(now, zone), cfg.morningAt, zone)
        val node = Playbook.today(app.store.plans.value, now, zone)?.let { Playbook.nextNode(it, now, zone) }
        return listOfNotNull(start - 10 * 60_000, start, start + 5 * 60_000 + 5_000, morning, morning + 86_400_000L, start + 86_400_000L - 10 * 60_000, node?.plus(2_000))
            .filter { it > now }.minOrNull()
    }
}
