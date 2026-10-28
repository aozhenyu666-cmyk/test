package com.zongkong.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.ChatTopic
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.agent.ChatMode
import com.zongkong.core.agent.OverallSnapshot
import com.zongkong.core.plan.Aims
import com.zongkong.core.plan.Directive
import com.zongkong.core.plan.Plans
import com.zongkong.core.vein.VStatus
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Rec
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统筹规划部的「全局」页：进来第一眼先看清整体局面，再看要不要展开明细。
 *
 * 三层结构：
 *  1. 整体状态摘要（长期目标 / 本周交付 / 主线 / 待验证 / 受阻 / 归因）；
 *  2. 每块一个「聊聊…」入口，跳进「整体」对话并预填主题（只预填，不发送、不建待办）；
 *  3. 「计划与工具」折叠区：原来分散的规划卡片和工具都在里面，默认收起，入口逻辑原样保留。
 *
 * 所有数字都从当前有效的数据算；缺的、过期的、读不到的直接写出来，不补一个看起来reasonable的进度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalOverview(work: Work, now: Long, nav: NavHostController, tools: @Composable () -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val zone = DayClock.BEIJING
    val today = remember(now) { DayClock.dateKey(now, zone) }
    val book by app.store.plans.collectAsStateWithLifecycle()
    val vein by app.store.vein.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val weekPlan = remember(book, now) { Plans.active(book, now, zone) }
    val progress = remember(weekPlan, work, now) { weekPlan?.let { Plans.progress(it, work, now, zone) } }
    val aims = remember(book) { Aims.open(book) }
    val hypo = remember(vein) { vein.nodes.filter { it.status == VStatus.HYPO.label } }
    val directives = remember(config) { config.directives.filter { it.status == Directive.STATUS_ACTIVE } }
    val mainAction = remember(book, work, now) { Plans.mainAction(book, work, now, zone) }
    val late = remember(work, today) {
        work.actions().filter { a ->
            a.actionStatus.open && a.plan?.let { p -> TimeParse.startMillis(p, zone)?.let { DayClock.dateKey(it, zone) < today } == true } == true
        }
    }
    val stuck = remember(work) { work.actions().filter { it.actionStatus == ActionStatus.STUCK } }
    val unplanned = remember(work) { Focus.unplannedThreads(work) }

    // 一手：这页只显示带时间的读数；读不到就写读不到，不当成 0，也不拿旧结果冒充
    val context = LocalContext.current
    val yishou by produceState<OverallSnapshot.External?>(initialValue = null, key1 = now / 60_000) {
        val at = System.currentTimeMillis()
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (val res = com.zongkong.app.link.YishouClient.status(context)) {
                    is com.zongkong.app.link.YishouClient.Result.Ok ->
                        OverallSnapshot.External("一手", text = "一手 " + res.value.appVersion + "：" + res.value.summary(), readAt = at)
                    is com.zongkong.app.link.YishouClient.Result.Err ->
                        OverallSnapshot.External("一手", error = res.message, readAt = at)
                }
            }.getOrElse { OverallSnapshot.External("一手", error = it.message ?: "读取失败", readAt = at) }
        }
    }

    // ---------- 1. 整体状态摘要 ----------
    Panel(accent = sig.plan, modifier = Modifier.enter(2).testTag("global-overview")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("整体状态", style = MaterialTheme.typography.titleMedium.merge(SerifTitle), modifier = Modifier.weight(1f))
            Text(SnapshotClock(now), style = MaterialTheme.typography.labelSmall, color = sig.muted)
        }
        Hint(if (aims.isEmpty() && weekPlan == null) "长期目标和本周计划都还没有：这一页现在能说的只有今天的事。" else "先看局面，再决定今天做什么。数字都来自当前的记录，没有的都写着。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill("长期目标", if (aims.isEmpty()) "未记录" else "${aims.size} 个", if (aims.isEmpty()) sig.muted else sig.plan)
            StatPill(
                "本周交付",
                when {
                    weekPlan == null -> "未立项"
                    progress == null -> "—"
                    else -> "${weekPlan.doneCount}/${weekPlan.outcomes.size}"
                },
                if (weekPlan == null) sig.muted else sig.free,
            )
            StatPill("待验证", if (hypo.isEmpty()) "没有" else "${hypo.size}", if (hypo.isEmpty()) sig.muted else sig.think)
            StatPill("受阻", if (late.size + stuck.size == 0) "没有" else "${late.size + stuck.size}", if (late.size + stuck.size == 0) sig.muted else sig.warn)
            StatPill("谋划单", if (directives.isEmpty()) "没有" else "${directives.size} 份", if (directives.isEmpty()) sig.muted else sig.ai)
        }
    }

    // ---------- 2. 每块：结论 + 一个「聊聊…」入口 ----------
    Section("long_term_goals", "长期目标", OverallSnapshot.Topic.LONG_TERM_GOALS, nav, sig.plan) {
        if (aims.isEmpty()) {
            Hint("还没有长期目标。想清楚要什么，用「大统筹」把远方落到这周的日程上。")
        } else {
            aims.take(4).forEach { a ->
                val left = Aims.daysLeft(a, now, zone)
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            left == null -> "没有期限"
                            left >= 0 -> "还剩 " + left + " 天"
                            else -> "已过 " + (-left) + " 天"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (left != null && left < 0) sig.strict else sig.muted,
                    )
                }
                val next = Aims.nextMilestone(a)
                Hint(
                    when {
                        a.milestones.isEmpty() -> "里程碑：未记录"
                        next == null -> "里程碑都做完了（${a.milestones.size} 个）"
                        else -> "下一个：" + next.title + "（" + next.due.ifBlank { "没日期" } + "）· 近 7 天 " + Aims.recentSteps(a, work, now) + " 步"
                    },
                )
            }
        }
    }

    Section("weekly_outcomes", "本周交付", OverallSnapshot.Topic.WEEKLY_OUTCOMES, nav, sig.free) {
        if (weekPlan == null) {
            Hint(if (Plans.draft(book, now, zone) != null) "有一份草稿等着立项。" else "这周还没有立项的计划。")
        } else {
            weekPlan.goals.forEach { g ->
                Text(g.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                weekPlan.outcomesOf(g.id).forEach { o ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(if (o.done) "✓" else "○", color = if (o.done) sig.free else sig.muted, style = MaterialTheme.typography.bodySmall)
                        Text(" " + o.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Hint(
                "本周计划内 " + (progress?.planMinutes ?: 0) + " 分钟、计划外 " + (progress?.offMinutes ?: 0) + " 分钟；今天计划内 " +
                    (progress?.todayPlanMinutes ?: 0) + "/" + weekPlan.dailyMinutes + " 分钟",
            )
        }
    }

    Section("mainline", "当前主线与下一步", null, nav, MaterialTheme.colorScheme.primary) {
        if (mainAction == null) {
            Hint("今天还没有主线。定一条，比排满一天有用。")
        } else {
            Text("「" + mainAction.title + "」", style = MaterialTheme.typography.bodyLarge)
            Hint(
                buildString {
                    append("所在事项："); append(work.rec(mainAction.threadKey)?.title ?: "未记录")
                    append("｜为什么在前："); append(whyFirst(work, book, mainAction, now, zone))
                    append("｜做到什么算完："); append(mainAction[F.CRITERIA].ifBlank { "没写完成依据" })
                },
            )
        }
        if (unplanned.isNotEmpty()) {
            Hint("还有 " + unplanned.size + " 件事有下一步但没排时间。展开「计划与工具」可以逐个排。")
        }
    }

    Section("hypotheses", "待验证 / 受阻 / 偏离", OverallSnapshot.Topic.HYPOTHESES, nav, sig.think) {
        if (hypo.isEmpty() && late.isEmpty() && stuck.isEmpty()) {
            Hint("没有待验证的判断，也没有卡住或超期的行动。")
        }
        hypo.take(3).forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("假设", sig.think)
                Text(" " + n.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (n.verify.isBlank()) "没写验证方式" else "已验证？", style = MaterialTheme.typography.labelSmall, color = if (n.verify.isBlank()) sig.warn else sig.muted)
            }
        }
        late.take(3).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("超期", sig.warn)
                Text(" " + a.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        stuck.take(3).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("卡住", sig.strict)
                Text(" " + a.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a[F.STUCK].ifBlank { a[F.BREAK] }.take(12), style = MaterialTheme.typography.labelSmall, color = sig.muted)
            }
        }
        val off = progress
        if (weekPlan != null && off != null && off.offMinutes > 0) {
            Hint("这周计划外用了 " + off.offMinutes + " 分钟" + if (off.offDone.isNotEmpty()) "，做完的计划外：" + off.offDone.take(3).joinToString("、") else "")
        }
    }

    Section("execution_feedback", "执行反馈（一手 / 电脑）", OverallSnapshot.Topic.EXECUTION_FEEDBACK, nav, sig.ai) {
        val y = yishou
        when {
            y == null -> Hint("正在读一手…")
            !y.ok -> {
                Tag("读不到", sig.strict)
                Hint("一手：" + y.error.take(60) + "（" + SnapshotClock(y.readAt) + " 尝试）")
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag("一手", sig.free)
                    Spacer(Modifier.width(6.dp))
                    Text(SnapshotClock(y.readAt) + " 读的", style = MaterialTheme.typography.labelSmall, color = sig.muted)
                }
                Text(y.text.lines().filter { it.isNotBlank() }.take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Hint("连上不等于练完了：这里只是它自己报的状态。")
            }
        }
        val pc = remember(now / 30_000) { app.store.device.value.pc.takeIf { app.actions.device.ready } }
        if (pc == null) {
            Hint("电脑：未接入 / 当前读不到，没有它的监控数据。")
        } else {
            Hint("电脑：最后一次状态 " + SnapshotClock(pc.at) + "，今天 " + pc.todayMinutes + " 分钟（娱乐 " + pc.funMinutes + " 分钟）")
        }
    }

    // ---------- 3. 计划与工具（可折叠，默认收起） ----------
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth().enter(3).testTag("global-tools-toggle"),
    ) {
        Text(if (open) "收起「计划与工具」" else "展开「计划与工具」：本周计划、晨间部署、清单、预案、时间线")
    }
    AnimatedVisibility(open, enter = expandVertically(tween(220)) + fadeIn(tween(160)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            tools()
        }
    }
}

/**
 * 一块整体摘要：标题 + 可选的「聊聊…」入口 + 内容。
 * 入口只做两件事：切到「整体」、把整个主题预填进输入框；不发送、不建待办。
 */
@Composable
private fun Section(
    tag: String,
    title: String,
    topic: OverallSnapshot.Topic?,
    nav: NavHostController,
    color: androidx.compose.ui.graphics.Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    Panel(modifier = Modifier.enter(2).testTag("global-" + tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
            if (topic != null) {
                TextButton(
                    onClick = {
                        app.chatTopic = ChatTopic(ChatMode.OVERALL.key, topic.key, topic.chip, topic.prompt)
                        nav.navigate("chat")
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("global-topic-" + topic.key),
                ) { Text(topic.chip, color = sig.ai, style = MaterialTheme.typography.labelMedium) }
            }
        }
        content()
    }
}

@Composable
private fun StatPill(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val c by animateColorAsState(color, tween(240), label = "statPill")
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall.merge(Mono), color = c)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalSignals.current.muted)
    }
}

/** 只显示时分；今天以外的日子带上月/日，免得把昨天的话当成今天的。 */
private fun SnapshotClock(at: Long): String {
    if (at <= 0) return "时间不明"
    val zone = DayClock.BEIJING
    val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    return if (t.toLocalDate() == today) String.format("%02d:%02d", t.hour, t.minute)
    else String.format("%d/%d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
}

/** 主线为什么在前：只从记录里找依据，没有就说没有。 */
private fun whyFirst(work: Work, book: com.zongkong.core.plan.PlanBook, main: Rec, now: Long, zone: java.time.ZoneId): String {
    val plan = Plans.active(book, now, zone)
    val outcome = plan?.outcomes?.firstOrNull { it.actionKey == main.key || (it.threadKey.isNotBlank() && it.threadKey == main.threadKey) }
    return when {
        outcome != null -> "它是本周交付「" + outcome.title + "」的下一步"
        plan != null && Plans.inPlan(plan, main) -> "它在本周计划里"
        else -> "没有记录说明（也不在本周计划里）"
    }
}
