package com.zongkong.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.zongkong.core.hq.Hq
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.link.YishouClient
import com.zongkong.app.zk
import com.zongkong.core.Dept
import com.zongkong.core.GatePhase
import com.zongkong.core.Reason
import com.zongkong.core.Shortcut
import com.zongkong.core.Status
import com.zongkong.core.YishouStatus
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Handoff
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 今天：一打开就知道现在该干什么。
 * 从上到下：问候和状态 → 该你处理的提示（严管怎么解、早上部署、晚上复盘）→ 现在这一步 →
 * 四个部门 → 快捷按钮 → 等你处理 → 接下来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val now = rememberNow(10_000)
    val status = remember(config, day, now, work.session) { store.status(now) }
    val book by store.plans.collectAsStateWithLifecycle()
    val plan = remember(book, now) { com.zongkong.core.plan.Plans.active(book, now, store.zone) }
    val cur = remember(work, book, now) { Focus.current(work, now, store.zone, store.mainKey(now)) }
    val upcoming = remember(work, now, cur) { Focus.upcoming(work, cur.action?.key, now, store.zone, 4) }
    val attention = remember(work) { Focus.needsAttention(work) }
    val unplanned = remember(work) { Focus.unplannedThreads(work) }
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val notionReady = config.notion.workReady
    var resumeKey by remember { mutableIntStateOf(0) }
    var clipHandoff by remember { mutableStateOf(false) }
    var autoPassed by remember { mutableStateOf<List<String>>(emptyList()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    LaunchedEffect(resumeKey) {
        if (notionReady && System.currentTimeMillis() - work.lastSyncAt > 60_000) scope.launch { app.actions.work.sync() }
        scope.launch { autoPassed = runCatching { app.actions.autoVerify() }.getOrDefault(emptyList()) }
        if (store.awaitingGpt.isNotBlank()) {
            delay(400) // 等窗口拿到焦点才能读剪贴板
            clipHandoff = Handoff.contains(Gpt.readClipboard(context))
        }
    }
    val yishou by produceState<YishouStatus?>(null, resumeKey) {
        value = if (YishouClient.installed(context)) (YishouClient.status(context) as? YishouClient.Result.Ok)?.value else null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ControlHomeCard(nav)
        // ---------- 问候 + 状态 ----------
        val d = Instant.ofEpochMilli(now).atZone(store.zone)
        // 远方：长期目标挂在最上面，一个一弯月亮
        var editAim by remember { mutableStateOf<com.zongkong.core.plan.Aim?>(null) }
        var addAim by remember { mutableStateOf(false) }
        AimsStrip(book, now, onEdit = { editAim = it }, onAdd = { addAim = true })
        if (addAim || editAim != null) AimDialog(editAim) { addAim = false; editAim = null }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.enter(0)) {
            Column(Modifier.weight(1f)) {
                Text(greeting(d.hour), style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp))
                Text(
                    "%d月%d日 %s · %s".format(d.monthValue, d.dayOfMonth, "周" + "一二三四五六日"[d.dayOfWeek.value - 1], clock(now)),
                    style = MaterialTheme.typography.bodyMedium, color = sig.muted,
                )
            }
            val c = statusColor(status)
            Box(Modifier.padding(top = 10.dp).clickable { nav.navigate("rhythm") }) {
                Pill("${status.headline} ${status.doneCount}/${status.gates.size}", c, dot = status.strict)
            }
        }

        // ---------- 权限掉了：放在最上面 ----------
        val missing = remember(resumeKey, now / 60_000) { PermissionStatus.missingCritical(context) }
        if (missing.isNotEmpty()) {
            Panel(accent = sig.strict, onClick = { nav.navigate("settings/perm") }, modifier = Modifier.enter(1).testTag("perm-banner")) {
                val guardDown = missing.any { it.startsWith("无障碍服务") }
                val sentinelOn = missing.none { it.startsWith("哨兵") }
                Text(if (guardDown && sentinelOn) "总控的眼睛掉了，哨兵在替它拦着" else if (guardDown) "总控现在管不住你" else "还差一双眼睛", style = MaterialTheme.typography.titleSmall, color = sig.strict)
                Hint(
                    "没开：" + missing.joinToString("、") + "。" +
                        (if (guardDown && sentinelOn) "娱乐应用先关着，修好总控就恢复。" else if (guardDown) "定时的总控时间、拦截、叫醒都会失灵。" else "总控一掉线就没人管。") +
                        "点这里，一项一项开。",
                )
            }
        }

        // ---------- 该你处理的提示 ----------
        Guidance(status, day.date, now, nav)
        if (autoPassed.isNotEmpty()) {
            Panel(accent = sig.free, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stamp("已验", sig.free, size = 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text("自动验收通过", style = MaterialTheme.typography.titleSmall)
                        Hint(autoPassed.joinToString("、") + "：证据够了，不用你交。")
                    }
                }
            }
        }
        if (clipHandoff) {
            Panel(accent = sig.think, onClick = { clipHandoff = false; nav.navigate("import") }, modifier = Modifier.enter(1)) {
                Text("剪贴板里有 GPT 的交接块", style = MaterialTheme.typography.titleSmall)
                Hint("点这里导入：结论、下一步、行动会写进事项，并同步到 Notion。")
            }
        }

        // ---------- 现在这一步 ----------
        Box(Modifier.enter(2)) {
            AnimatedContent(
                targetState = cur,
                contentKey = { it.action?.key to it.why },
                transitionSpec = { (fadeIn() + slideInVertically { it / 8 }) togetherWith fadeOut() },
                label = "task",
            ) { c -> TaskCard(c, now, notionReady, nav, plan) }
        }

        // ---------- 今天：计划、总控时间、电脑、信用 ----------
        SectionLabel("今天")
        PlanStrip(plan, book, work, now, nav)

        val hqBook by store.hq.collectAsStateWithLifecycle()
        HqStrip(config.hq, hqBook, now, store.zone, nav)

        ScriptLine(nav)
        TodayLine(nav)
        PcStrip(nav)
        PartnerLine(nav)

        // ---------- 接下来 ----------
        if (upcoming.isNotEmpty()) {
            SectionLabel("接下来")
            Panel(modifier = Modifier.enter(9)) {
                upcoming.forEach { a -> TimelineRow(a, work, now, nav) }
            }
        }

        // ---------- 等你处理 ----------
        if (attention.isNotEmpty() || unplanned.isNotEmpty()) {
            SectionLabel("等你处理")
            Panel(modifier = Modifier.enter(8)) {
                attention.take(4).forEach { a ->
                    val label = if (a.actionStatus == ActionStatus.CONFIRM) "待确认：结果够不够" else "卡住：${a[F.STUCK].ifBlank { "原因没写" }}"
                    MiniRow(a.title, label, actionColor(a.actionStatus)) { nav.navigate("action/${a.key}") }
                }
                unplanned.take(3).forEach { t ->
                    MiniRow("${t.title}：${t[F.NEXT]}", "下一步还没排进时间 · 点开排", sig.warn) { nav.navigate("thread/${t.key}") }
                }
            }
        }

        // ---------- 四个部门 ----------
        SectionLabel("四个部门")
        val depts = listOf(Dept.INFO, Dept.THINK, Dept.PLAN, Dept.ACT)
        depts.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { i, dept ->
                    DeptTile(dept, status, work, now, yishou, Modifier.weight(1f).enter(3 + row * 2 + i)) { nav.navigate("dept/${dept.name}") }
                }
            }
        }

        // ---------- 快捷 ----------
        SectionLabel("随手")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.enter(7)) {
            QuickChip("收一个念头", sig.info) { nav.navigate("capture") }
            QuickChip("我卡住了", sig.strict) { nav.navigate("stuckpick") }
            QuickChip("问总控", sig.ai) { nav.navigate("chat") }
            val n = work.changes.count { it.at > store.seenChanges }
            QuickChip(if (n > 0) "查看结果 · $n" else "查看结果", sig.think) { nav.navigate("results") }
            config.shortcuts.filter { it.dept == null }.forEach { s -> QuickChip(s.label, sig.ai) { runShortcut(s, app, nav) } }
        }

        SyncLine(work, notionReady) { scope.launch { app.actions.work.sync() } }
        Spacer(Modifier.height(8.dp))
    }
}

fun greeting(hour: Int): String = when (hour) {
    in 0..4 -> "夜深了。"
    in 5..8 -> "早上好。"
    in 9..11 -> "上午好。"
    in 12..13 -> "中午好。"
    in 14..17 -> "下午好。"
    in 18..22 -> "晚上好。"
    else -> "夜深了。"
}

@Composable
fun statusColor(status: Status): Color {
    val sig = LocalSignals.current
    return when {
        status.paused -> sig.muted
        status.emergencyUntil != null -> sig.warn
        status.strict -> sig.strict
        else -> sig.free
    }
}

/**
 * 轮椅式的提示：此刻你只需要看这一条。
 * 严管时说清楚怎么解除；早上没部署就带你去部署；晚上没复盘就带你去复盘。
 */
/** 首页上的总控时间条：进行中、快到了、到点了都显眼；其余时候一行小字。 */
@Composable
private fun HqStrip(cfg: com.zongkong.core.hq.HqConfig, book: com.zongkong.core.hq.HqBook, now: Long, zone: java.time.ZoneId, nav: NavHostController) {
    val sig = LocalSignals.current
    val active = book.active
    val due = remember(cfg, book, now) { Hq.due(cfg, book.sessions, now, zone) }
    val at = "%02d:%02d".format(cfg.at / 60, cfg.at % 60)
    val (title, sub, loud) = when {
        active != null -> Triple("总控时间进行中", "第 ${active.step + 1}/6 步「${active.current.title}」· 到 ${clock(active.endsAt)}", true)
        due == Hq.Due.NOW || due == Hq.Due.OVERDUE -> Triple("总控时间到了", "放下手里的，进来 ${cfg.minutes} 分钟。", true)
        due == Hq.Due.SOON -> Triple("$at 总控时间", "快到了：先把手头这件收个尾。", false)
        Hq.doneToday(cfg, book.sessions, now, zone) -> Triple("今天的总控时间做过了", "明天 $at 再见。", false)
        cfg.enabled -> Triple("今晚 $at 总控时间", "${cfg.minutes} 分钟，万籁俱寂，只和总控说话。", false)
        else -> Triple("总控时间", "没开每日的；想静下来理一理，随时可以进。", false)
    }
    Panel(
        accent = if (loud) sig.hq else null,
        onClick = { nav.navigate("hq") },
        modifier = Modifier.enter(2).testTag("hq-strip"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Seal("总", sig.hq, size = 36.dp, filled = loud)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Hint(sub)
            }
        }
    }
}

/** 远方：长期目标。没有就只是一道底纹和一个小小的“＋ 远方”。 */
@Composable
private fun AimsStrip(book: com.zongkong.core.plan.PlanBook, now: Long, onEdit: (com.zongkong.core.plan.Aim) -> Unit, onAdd: () -> Unit) {
    val sig = LocalSignals.current
    val zone = com.zongkong.core.DayClock.BEIJING
    val aims = com.zongkong.core.plan.Aims.open(book)
    if (aims.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(28.dp)) {
            FlowLines(sig.line, Modifier.fillMaxSize(), lines = 3)
            Text(
                "＋ 远方", style = MaterialTheme.typography.labelMedium, color = sig.muted,
                modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onAdd).padding(6.dp).testTag("aim-add"),
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("aims")) {
        // 一根细杆，月亮吊在下面
        Box(Modifier.fillMaxWidth().height(1.dp).background(sig.line.copy(alpha = 0.6f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aims.forEach { a ->
                val left = com.zongkong.core.plan.Aims.daysLeft(a, now, zone)
                val color = if (left != null && left <= 7) sig.warn else sig.plan
                Column(Modifier.width(76.dp).clickable { onEdit(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    // 月亮很轻地呼吸，像是还在走
                    MoonPhase(com.zongkong.core.plan.Aims.phase(a, now, zone), color, Modifier.width(40.dp).height(56.dp).breathe(0.02f, 5600))
                    Text(a.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        left?.let { if (it >= 0) "还剩 $it 天" else "过期 ${-it} 天" } ?: "没有期限",
                        style = MaterialTheme.typography.labelSmall.merge(Mono), color = if (left != null && left <= 7) sig.warn else sig.muted,
                    )
                }
            }
            if (aims.size < com.zongkong.core.plan.Aims.MAX) {
                Text("＋", style = MaterialTheme.typography.titleMedium, color = sig.muted, modifier = Modifier.padding(top = 18.dp).clickable(onClick = onAdd).padding(8.dp).testTag("aim-add"))
            }
        }
    }
}

/** 挂一个 / 改一个长期目标。[aim] 空 = 新建。 */
@Composable
fun AimDialog(aim: com.zongkong.core.plan.Aim?, onDismiss: () -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    var title by remember { mutableStateOf(aim?.title.orEmpty()) }
    var why by remember { mutableStateOf(aim?.why.orEmpty()) }
    var deadline by remember { mutableStateOf(aim?.deadline.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (aim == null) "挂一个远方" else "远方") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("周计划装不下的长期目标：求职、考试、作品集……不排日程，只挂在首页上方。月亮从新月长到满月 = 时间走了多少。和总控聊的时候说一句，它会帮你关联到事项、在周计划里留出一步。")
                androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("目标") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("aim-title"))
                androidx.compose.material3.OutlinedTextField(why, { why = it }, label = { Text("为什么要它（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(deadline, { deadline = it; err = null }, label = { Text("截止日（12-20 / 2027-03-01，可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = sig.strict, style = MaterialTheme.typography.bodySmall) }
                if (aim != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { app.store.updatePlans { b -> com.zongkong.core.plan.Aims.close(b, aim.id, now).getOrDefault(b) }; onDismiss() }) { Text("达成 / 放下", color = sig.free) }
                        TextButton(onClick = { app.store.updatePlans { com.zongkong.core.plan.Aims.remove(it, aim.id) }; onDismiss() }) { Text("删掉", color = sig.muted) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val dl = deadline.trim().takeIf { it.isNotEmpty() && it != aim?.deadline }?.let { raw ->
                    val ms = TimeParse.parse(raw, now, app.store.zone)?.let { TimeParse.startMillis(it, app.store.zone) }
                    if (ms == null) { err = "截止日认不出：$raw"; return@TextButton }
                    Instant.ofEpochMilli(ms).atZone(app.store.zone).toLocalDate()
                }
                var fail: String? = null
                app.store.updatePlans { b ->
                    com.zongkong.core.plan.Aims.set(b, aim?.id.orEmpty(), title, why, dl, null, now) { "aim" + (now / 1000).toString(36).takeLast(5) }
                        .getOrElse { e -> fail = e.message; b }
                }
                if (fail != null) err = fail else onDismiss()
            }, modifier = Modifier.testTag("aim-save")) { Text("挂上") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 电脑：没连时提示去连；连上后今天电脑用了多久、娱乐多少、被拦几次、锁没锁。 */
@Composable
private fun PcStrip(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val config by app.store.config.collectAsStateWithLifecycle()
    val book by app.store.device.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val pc = book.pc.takeIf { config.notion.devicesDb.isNotBlank() }
    Panel(onClick = { nav.navigate("settings/pc") }, accent = if (pc == null) sig.warn else null, modifier = Modifier.testTag("pc-strip")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Seal("电", if (pc == null) sig.warn else sig.plan, size = 40.dp, filled = pc != null)
            Column(Modifier.weight(1f)) {
                if (pc == null) {
                    Text("电脑还没管起来", style = MaterialTheme.typography.titleSmall)
                    Hint("装电脑版、和手机连起来：先说用途才能用、娱乐有额度、夜里锁机，总控时间电脑跟着锁。")
                } else {
                    val locked = pc.lockUntil > now
                    Text("电脑今天 ${pc.todayMinutes / 60} 小时 ${pc.todayMinutes % 60} 分 · 娱乐 ${pc.funMinutes} 分", style = MaterialTheme.typography.titleSmall)
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        text = listOfNotNull(
                            if (locked) "锁着" else if (pc.sessionUntil > now) "在做：${pc.session}" else null,
                            if (pc.blocks > 0) "被拦 ${pc.blocks} 次" else null,
                            if (pc.gaps.isNotEmpty()) "电脑上的总控被关过 ${pc.gaps.size} 次" else null,
                            if (now - pc.at > 10 * 60_000L) "${(now - pc.at) / 60_000} 分钟前同步" else null,
                        ).joinToString(" · ").ifBlank { "在线" },
                        color = if (pc.gaps.isNotEmpty()) sig.strict else sig.muted,
                    )
                }
            }
            Text("→", style = MaterialTheme.typography.titleMedium, color = sig.muted)
        }
    }
}

/** 战友：信用、态度、现在的强度。点开看档案。 */
/** 今天的预案（有的话）：现在这段 / 下一段。 */
@Composable
private fun ScriptLine(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val book by app.store.plans.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val s = remember(book, now) { com.zongkong.core.plan.Playbook.today(book, now, app.store.zone) } ?: return
    val pb = com.zongkong.core.plan.Playbook
    val cur = pb.current(s, now, app.store.zone)
    val next = pb.next(s, now, app.store.zone)
    val late = pb.overdue(s, now, app.store.zone)
    Panel(accent = if (late.isNotEmpty()) sig.warn else sig.plan, onClick = { nav.navigate("playbook") }, modifier = Modifier.testTag("script-line")) {
        Text(if (s.locked) "预案" else "预案（草稿，还没定稿）", style = MaterialTheme.typography.labelLarge, color = sig.plan)
        when {
            cur != null -> Text("现在 ${cur.span} ${cur.what}", style = MaterialTheme.typography.titleSmall)
            next != null -> Text("下一段 ${next.span} ${next.what}", style = MaterialTheme.typography.titleSmall)
            else -> Text("今天的段都过了", style = MaterialTheme.typography.titleSmall)
        }
        if (late.isNotEmpty()) Hint("过了点还没交代：" + late.joinToString("、") { it.what.take(10) } + "。做完、走 B 还是跳过？")
        else (cur ?: next)?.planB?.takeIf { it.isNotBlank() }?.let { Hint("B：$it") }
    }
}

/** 今天的清单：做完几步、没做几步，点进去看全部。 */
@Composable
private fun TodayLine(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val work by app.store.work.collectAsStateWithLifecycle()
    val today by app.store.today.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val r = remember(work, today, now) { app.actions.dayReport(now) }
    if (r.empty) return
    Panel(onClick = { nav.navigate("today") }, modifier = Modifier.testTag("today-line")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(if (r.planned.isEmpty()) 1f else r.doneCount / r.planned.size.toFloat(), sig.free, size = 40.dp, stroke = 3.dp) {
                AnimatedInt(r.doneCount, style = MaterialTheme.typography.labelMedium.merge(Mono), color = sig.free)
            }
            Column(Modifier.weight(1f)) {
                Text("今天的清单", style = MaterialTheme.typography.titleSmall)
                val notDone = r.notDone.size
                Hint(buildString {
                    if (r.planned.isNotEmpty()) append("排了 ${r.planned.size} 步，做完 ${r.doneCount}") else append("没排")
                    if (notDone > 0) append("，还有 $notDone 步没做")
                    if (r.extra.isNotEmpty()) append("，另外做了 ${r.extra.size} 样")
                })
            }
            Text("→", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        }
    }
}

@Composable
private fun PartnerLine(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val config by app.store.config.collectAsStateWithLifecycle()
    val d by app.store.dossier.collectAsStateWithLifecycle()
    val now = rememberNow(60_000)
    val cred = remember(d, now) { app.store.credibility(now) }
    val st = remember(d, now) { app.store.stance(now) }
    Panel(onClick = { nav.navigate("dossier") }, modifier = Modifier.testTag("partner-line")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(cred / 100f, stanceColor(st), size = 40.dp, stroke = 3.dp) {
                AnimatedInt(cred, style = MaterialTheme.typography.labelMedium.merge(Mono), color = stanceColor(st))
            }
            Column(Modifier.weight(1f)) {
                Text("${st.label} · ${com.zongkong.core.boss.Intensity.short(config.boss, st)}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(if (cred < 60) "及时回话、按时开始、做完一步、守住约定，信用就往回爬。" else st.rule, modifier = Modifier)
            }
        }
    }
}

@Composable
private fun PlanStrip(plan: com.zongkong.core.plan.WeekPlan?, book: com.zongkong.core.plan.PlanBook, work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val zone = com.zongkong.core.DayClock.BEIJING
    if (plan == null) {
        val draft = com.zongkong.core.plan.Plans.draft(book, now, zone)
        Panel(accent = if (draft != null) sig.ai else null, onClick = { nav.navigate("plan") }, modifier = Modifier.enter(2).testTag("plan-strip")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeptSeal(Dept.PLAN, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (draft != null) "有一份计划草稿等你点头" else "这周还没有参照", style = MaterialTheme.typography.titleSmall)
                    Hint(if (draft != null) "${if (draft.by == "参谋") "总控" else draft.by}起草的：${draft.goals.joinToString("、") { it.title }}" else "五分钟定个计划：几个方向、交出什么、每天多少时间。不想想就让总控起草。")
                }
                Text("→", style = MaterialTheme.typography.titleMedium, color = sig.plan)
            }
        }
        return
    }
    val main = com.zongkong.core.plan.Plans.mainAction(book, work, now, zone)
    val pr = remember(plan, work, now) { com.zongkong.core.plan.Plans.progress(plan, work, now, zone) }
    Panel(onClick = { nav.navigate("plan") }, modifier = Modifier.enter(2).testTag("plan-strip")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(if (plan.outcomes.isEmpty()) 0f else plan.doneCount.toFloat() / plan.outcomes.size, sig.plan, size = 44.dp, stroke = 3.5.dp) {
                AnimatedInt(
                    plan.doneCount,
                    style = MaterialTheme.typography.labelMedium.merge(Mono),
                    color = sig.plan,
                    suffix = "/${plan.outcomes.size}",
                )
            }
            Column(Modifier.weight(1f)) {
                Text(plan.goals.joinToString(" · ") { it.title }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (main?.let { "主线：${it.title}" } ?: "今天的主线还没定 · 点这里挑") + " · 计划内 ${pr.todayPlanMinutes}/${plan.dailyMinutes} 分",
                    style = MaterialTheme.typography.bodySmall, color = if (main == null) sig.warn else sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Guidance(status: Status, date: String, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val app = LocalContext.current.zk
    val scope = rememberCoroutineScope()
    val hour = Instant.ofEpochMilli(now).atZone(com.zongkong.core.DayClock.BEIJING).hour
    val reason = status.reasons.firstOrNull()
    val planOpen = status.gates.firstOrNull { it.gate.id == com.zongkong.app.data.Actions.PLAN_GATE && !it.done && it.phase != GatePhase.NOT_OPEN }
    // 今天已经排了（你自己排的，或者总控早上替你排的）：看一眼、点一下确认就算部署
    val planned = remember(status, app.store.work.value) { Focus.evidence(app.store.work.value, Focus.Evidence.PLANNED_TODAY, now, com.zongkong.core.DayClock.BEIJING) }
    when {
        planOpen != null && planned.first > 0 -> {
            Panel(accent = sig.plan, onClick = { scope.launch { app.actions.autoVerify(confirmPlan = true) } }, modifier = Modifier.enter(1).testTag("guide-confirm-plan")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeptSeal(Dept.PLAN, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text("今天排了 ${planned.first} 步，确认一下就开始", style = MaterialTheme.typography.titleSmall)
                        Hint(planned.second.joinToString("、") { "「$it」" } + "。要改就去晨间部署。")
                    }
                    Text("确认 →", style = MaterialTheme.typography.labelLarge, color = sig.plan)
                }
            }
        }
        status.strict && reason != null -> {
            val (title, how, route) = when (reason) {
                is Reason.GateDue -> Triple(
                    "「${reason.status.gate.title}」还没交",
                    if (reason.status.gate.id == "plan_morning") "点这里，跟着向导两分钟部署完就放行" else "交了就放行，点这里去交",
                    if (reason.status.gate.id == "plan_morning") "wizard/plan" else "gate/${reason.status.gate.id}",
                )
                is Reason.Silence -> Triple("太久没汇报了", "报个到：说一句现在在做什么", "capture")
                is Reason.Quota -> Triple("今天的娱乐额度用完了", "明天凌晨 4 点重置", "rhythm")
                is Reason.Session -> Triple("总控时间进行中", "回到总控时间，做完就解锁", "hq")
                is Reason.Absent -> Triple("已经 ${reason.days} 天没和总控交流了", "跟总控说几句，或者进一次总控时间", "hq")
                is Reason.Focus -> Triple("专注锁开着", "做完「${reason.title}」就解开", "session")
                is Reason.Budget -> Triple("今天挣到的 ${reason.allowedMin} 分钟娱乐用完了", "做完一步再挣 20 分钟", "today")
                is Reason.Debt -> Triple("「${reason.title}」过了点还没开始", "现在开始，或者改个时间 / 标卡住", "start/${reason.actionKey}")
                is Reason.Hold -> Triple(reason.text, "你和墨灵约好的，到点自己解除", "home")
            }
            Panel(accent = sig.strict, onClick = { nav.navigate(route) }, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Seal("严", sig.strict, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = sig.strict)
                        Text(how, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("去 →", style = MaterialTheme.typography.labelLarge, color = sig.strict)
                }
                if (status.reasons.size > 1) Hint("另外还有 ${status.reasons.size - 1} 条，一条条来。")
            }
        }
        status.gates.any { it.gate.id == "plan_morning" && !it.done && it.phase != GatePhase.NOT_OPEN } -> {
            Panel(accent = sig.plan, onClick = { nav.navigate("wizard/plan") }, modifier = Modifier.enter(1).testTag("guide-plan")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeptSeal(Dept.PLAN, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text("先花两分钟部署今天", style = MaterialTheme.typography.titleSmall)
                        Hint("一步一步问你：今天最要紧的一件、第一步、几点做、做到什么算完。")
                    }
                    Text("开始 →", style = MaterialTheme.typography.labelLarge, color = sig.plan)
                }
            }
        }
        hour >= 20 && status.gates.any { it.gate.id == "hq_review" && !it.done } -> {
            Panel(accent = sig.hq, onClick = { nav.navigate("today") }, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeptSeal(Dept.HQ, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text("看一眼今天的清单", style = MaterialTheme.typography.titleSmall)
                        Hint("总控照记录列好了：做了什么、没做什么。补一句明天第一步就能交。")
                    }
                    Text("开始 →", style = MaterialTheme.typography.labelLarge, color = sig.hq)
                }
            }
        }
    }
}

@Composable
private fun DeptTile(dept: Dept, status: Status, work: Work, now: Long, yishou: YishouStatus?, modifier: Modifier, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val color = sig.dept(dept)
    val zone = com.zongkong.core.DayClock.BEIJING
    val gates = status.gates.filter { it.gate.dept == dept }
    val (line, progress) = when (dept) {
        Dept.INFO -> Focus.evidence(work, Focus.Evidence.LINKED_NOTES, now, zone).first.let { "今天挂上 $it 条" to null }
        Dept.THINK -> Focus.evidence(work, Focus.Evidence.CONCLUSIONS, now, zone).first.let { "今天 $it 个判断" to null }
        Dept.PLAN -> Focus.evidence(work, Focus.Evidence.PLANNED_TODAY, now, zone).first.let { "今天排了 $it 步" to null }
        Dept.ACT -> when {
            work.session?.let { it.lockUntil > now } == true -> "专注锁到 ${clock(work.session!!.lockUntil)}" to null
            yishou != null -> "一手 ${yishou.doneSets}/${yishou.targetSets} 组" to (if (yishou.targetSets > 0) yishou.doneSets.toFloat() / yishou.targetSets else null)
            else -> "一手没连上" to null
        }
        Dept.HQ -> "" to null
    }
    val ring = progress ?: if (gates.isEmpty()) 0f else gates.count { it.done }.toFloat() / gates.size
    val source = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .pressScale(source)
            .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
            .testTag("dept-${dept.name}"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(ring, color, size = 46.dp, stroke = 3.5.dp) {
                    Text(dept.seal, color = color, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Spacer(Modifier.weight(1f))
                val overdue = gates.any { it.phase == GatePhase.OVERDUE }
                if (overdue) PulseDot(sig.strict) else if (gates.isNotEmpty() && gates.all { it.done }) Text("✓", color = sig.free, fontWeight = FontWeight.Black)
            }
            Column {
                Text(dept.label, style = MaterialTheme.typography.titleSmall)
                Text(line, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun QuickChip(label: String, color: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.pressScale(source).clickable(interactionSource = source, indication = ripple(), onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(6.dp).height(6.dp).background(color, RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 参谋加的快捷按钮：ask 发给参谋，open 打开页面。 */
fun runShortcut(s: Shortcut, app: com.zongkong.app.ZkApp, nav: NavHostController) {
    if (s.kind == Shortcut.ASK) {
        app.chatDraft = s.payload
        nav.navigate("chat") { launchSingleTop = true }
    } else {
        val (p, t) = s.payload.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        app.chat.agent.tools.route(p, t)?.let { r -> runCatching { nav.navigate(r) } }
    }
}

@Composable
private fun TimelineRow(a: com.zongkong.core.work.Rec, work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val zone = com.zongkong.core.DayClock.BEIJING
    val start = a.plan?.let { TimeParse.startMillis(it, zone) }
    Row(
        Modifier.fillMaxWidth().clickable { nav.navigate("action/${a.key}") }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
            Text(start?.let { clock(it) } ?: "—", style = MaterialTheme.typography.titleSmall.merge(Mono), color = if (start != null) MaterialTheme.colorScheme.onSurface else sig.muted)
            a.plan?.let { p ->
                val label = TimeParse.label(p, now, zone)
                if (!label.startsWith("今天")) Text(label.substringBefore(' '), style = MaterialTheme.typography.labelSmall, color = sig.muted)
            }
        }
        Box(Modifier.width(3.dp).height(34.dp).background(actionColor(a.actionStatus), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            work.rec(a.threadKey)?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun TaskCard(cur: Focus.Current, now: Long, notionReady: Boolean, nav: NavHostController, plan: com.zongkong.core.plan.WeekPlan? = null) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val a = cur.action
    val t = cur.thread
    val accent = when (cur.why) {
        Focus.Why.RUNNING -> sig.warn
        Focus.Why.NOW -> MaterialTheme.colorScheme.primary
        Focus.Why.OVERDUE -> sig.strict
        Focus.Why.CONTINUE -> sig.think
        else -> sig.muted
    }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(260))
            .then(
                // 正在做 / 已超时：整张卡极轻地呼吸，像还活着
                if (cur.why == Focus.Why.RUNNING || cur.why == Focus.Why.OVERDUE) Modifier.breathe(0.006f, 3800) else Modifier,
            ),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cur.why == Focus.Why.RUNNING || cur.why == Focus.Why.OVERDUE) PulseDot(accent)
                MorphText(
                    if (a == null) "现在" else cur.why.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = accent,
                    modifier = Modifier.weight(1f).padding(start = if (cur.why == Focus.Why.RUNNING || cur.why == Focus.Why.OVERDUE) 8.dp else 0.dp),
                )
                cur.start?.let { s ->
                    val label = when {
                        cur.why == Focus.Why.RUNNING -> app.store.work.value.session?.let { "已做 ${span(now - it.startedAt)}" } ?: ""
                        s > now -> "${span(s - now)}后"
                        cur.end != null && cur.end > now -> "还剩 ${span(cur.end - now)}"
                        cur.end != null -> "超时 ${span(now - cur.end)}"
                        else -> ""
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge.merge(Mono), color = accent)
                }
            }
            if (a == null) {
                Text("没有排好的下一步", style = MaterialTheme.typography.headlineMedium)
                Text("不用想太多：让总控陪你挑一件，或者跟着向导部署一下。", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
                BigButton("跟着向导排一件", { nav.navigate("wizard/plan") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("chat") }, modifier = Modifier.weight(1f)) { Text("问总控") }
                    OutlinedButton(onClick = { nav.navigate("import") }, modifier = Modifier.weight(1f)) { Text("导入交接块") }
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t?.let { Text("${it.title} · ${it[F.CODE]}", style = MaterialTheme.typography.labelLarge, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                if (plan != null) {
                    if (com.zongkong.core.plan.Plans.inPlan(plan, a)) Tag("计划内", sig.plan) else Tag("计划外", sig.warn)
                }
            }
            Text(a.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("task-title").clickable { nav.navigate("action/${a.key}") })
            a.plan?.let { Text(TimeParse.label(it, now, app.store.zone), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted) }
            Column(
                Modifier.fillMaxWidth().background(sig.wash.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardLine("为什么", t?.get(F.WHY).orEmpty(), placeholder = "")
                CardLine("停在", a[F.BREAK].ifBlank { t?.get(F.BREAK).orEmpty() })
                CardLine("做到", a[F.CRITERIA], placeholder = "还没写完成依据——点标题写一句：做到什么算完")
            }
            when (cur.why) {
                Focus.Why.RUNNING -> BigButton("回到正在做的", { nav.navigate("session") }, color = sig.warn)
                else -> BigButton(
                    if (cur.why == Focus.Why.CONTINUE) "从断点继续" else "开始",
                    { nav.navigate("start/${a.key}") },
                    Modifier.testTag("task-start"),
                    color = if (cur.why == Focus.Why.OVERDUE) sig.strict else MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (t != null) {
                        app.prompt = app.actions.work.gptPrompt(t.key)
                        app.promptTitle = t.title
                        app.store.awaitingGpt = t.key
                        nav.navigate("gpt")
                    } else {
                        nav.navigate("action/${a.key}")
                    }
                }, modifier = Modifier.testTag("task-gpt")) { Text("去 GPT") }
                TextButton(onClick = { nav.navigate("stuck/${a.key}") }) { Text("卡住了", color = sig.muted) }
                if (t != null && t.url.isNotBlank()) TextButton(onClick = { Gpt.openNotion(context, t.url) }) { Text("Notion", color = sig.muted) }
                Box(Modifier.weight(1f))
                SyncTag(a, notionReady)
            }
        }
    }
}

@Composable
private fun CardLine(label: String, value: String, placeholder: String = "") {
    if (value.isBlank() && placeholder.isBlank()) return
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted, modifier = Modifier.padding(top = 2.dp).width(44.dp))
        Text(
            value.ifBlank { placeholder }, style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) LocalSignals.current.warn else MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MiniRow(title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(color, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
