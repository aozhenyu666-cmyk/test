package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqTrigger
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
            if (now > s.endsAt + 60 * 60_000L) app.actions.hq.exitEarly("超时一小时没封存，自动结束")
        }

        // 和电脑同步（总控时间开始 / 结束时马上同步，电脑跟着锁 / 解）
        app.actions.device.maybeSync()

        val due = Hq.due(cfg, store.hq.value.sessions, now, zone)
        // 进程刚被闹钟拉起来时服务可能还没连上：一分半内有过心跳也算在
        val eyes = com.zongkong.app.guard.GuardService.running || now - store.heartbeat < 90_000
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

        if (!cfg.proactive) return

        // 早上第一条
        if (minute in cfg.morningAt until cfg.morningAt + 180 && store.firstAlert("morning")) {
            app.chat.proactive(
                "morning",
                "[定时·早上] 早上了，给他发今天的第一条消息：今天的主线是什么（没定就问他要不要现在挑一个，给【选项】），再提醒一个他最可能卡住的地方（看计划里的预演）。最多三句。不要改任何东西。",
                fallback = "早上好。今天的主线定了吗？没定的话，打开总控挑一件。",
            )
        }

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
        val n = config.boss.spotChecks.coerceIn(0, 8)
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
            else -> generateSequence(activeStart + 60) { it + 90 }.takeWhile { it < activeEnd - 30 }.toList()
        }
        return base.map { (it + rnd.nextInt(-20, 21)).coerceIn(activeStart, activeEnd - 1) }
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
        val slots = checkInSlots(config.boss.initiative, config.activeStart, config.activeEnd, date)
        val i = slots.indexOfFirst { minute in it until it + 45 }
        if (i < 0 || !store.firstAlert("checkin:$i")) return null
        val main = Plans.mainAction(store.plans.value, store.work.value, now, store.zone)
        val hypo = store.vein.value.nodes.filter { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
            .maxByOrNull { com.zongkong.core.vein.Veins.daysBetween(it.checks.lastOrNull()?.date ?: it.created, store.dateToday(now)) }
        val intake = com.zongkong.core.info.Intake.stats(store.work.value, store.intake.value, now, store.zone)
        val fallback = when {
            main != null -> "「${main.title}」现在到哪一步了？回一句就行。"
            intake.backlog > 0 -> "收集箱里有 ${intake.backlog} 条还没提炼。挑一条，用一句话说它对你有什么用。"
            hypo != null -> "脉络里「${hypo.title}」还是假设。这周验证了吗？"
            else -> "现在在做什么？接下来一小时打算做什么？"
        }
        app.chat.proactive(
            "checkin",
            "[定时·问候] 主动找用户说一句（这次一定要说，不能回“无事”）。先看档案、今天的主线、未结的约定、intake_status、vein_due，" +
                "挑最该问的一件，问一个具体、马上能回答的问题；如果有约定快到点，就问那个。一两句，最后给【选项】。态度按档案里的来。",
            fallback = fallback,
        )
        Buzz.once(context, Buzz.Level.GENTLE)
        store.logWake("问候：第 ${i + 1} 次（主动程度 ${com.zongkong.core.boss.BossConfig.INITIATIVE.getOrElse(config.boss.initiative) { "?" }}）")
        return Unit
    }

    /** 记录时附上现在的状况：服务在不在、通知能不能发。 */
    private fun diag(context: Context): String = buildString {
        if (!com.zongkong.app.guard.GuardService.running && System.currentTimeMillis() - context.zk.store.heartbeat > 90_000) append("（无障碍服务没在运行）")
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
        return listOf(start - 10 * 60_000, start, start + 5 * 60_000 + 5_000, morning, morning + 86_400_000L, start + 86_400_000L - 10 * 60_000)
            .filter { it > now }.minOrNull()
    }
}
