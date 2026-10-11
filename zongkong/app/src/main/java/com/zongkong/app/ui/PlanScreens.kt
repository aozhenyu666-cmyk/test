package com.zongkong.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.plan.Goal
import com.zongkong.core.plan.IfThen
import com.zongkong.core.plan.Outcome
import com.zongkong.core.plan.PlanStatus
import com.zongkong.core.plan.Plans
import com.zongkong.core.plan.WeekPlan
import com.zongkong.core.work.F
import java.time.LocalDate

private fun weekLabel(ws: String): String {
    val d = LocalDate.parse(ws)
    val e = d.plusDays(6)
    return "${d.monthValue}.${d.dayOfMonth}–${e.monthValue}.${e.dayOfMonth}"
}

// ---------- 计划页 ----------

/**
 * 本周计划：参照，不是日程表。
 * 没有计划时：进入计划模式，或者让参谋起草。有草稿时：看一眼、点头立项。立项后：方向、交出物、今天的主线、时间花在哪。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val book by app.store.plans.collectAsStateWithLifecycle()
    val work by app.store.work.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val zone = app.store.zone
    val active = remember(book, now) { Plans.active(book, now, zone) }
    val next = remember(book, now) { Plans.nextActive(book, now, zone) }
    val draft = remember(book, now) { Plans.draft(book, now, zone) }
    var dropping by remember { mutableStateOf<Outcome?>(null) }
    var adding by remember { mutableStateOf<Goal?>(null) }
    var showLog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("本周计划", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- 草稿：等你点头 ----------
            if (draft != null) {
                Panel(accent = if (draft.by == "参谋") sig.ai else sig.plan, modifier = Modifier.enter(0).testTag("plan-draft")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (draft.by == "参谋") "参谋起草了一份计划" else "你的草稿", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Tag(weekLabel(draft.weekStart), sig.plan)
                    }
                    PlanBody(draft, showChecks = false)
                    Hint("计划不立项就不生效。立项后：没有事项的方向会建成事项，每样交出物在事项下建一步，首页开始按它推。")
                    BigButton("立项", {
                        app.actions.plan.approve(draft.id)
                    }, Modifier.testTag("plan-approve"), color = sig.plan)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate("plan/new") }, modifier = Modifier.weight(1f)) { Text("改一改") }
                        OutlinedButton(onClick = { app.actions.plan.discardDraft(draft.id) }, modifier = Modifier.weight(1f)) { Text("不要了") }
                    }
                }
            }

            if (active == null) {
                if (draft == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("这周还没有计划", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.enter(0))
                    Text(
                        "不用排满日程。只要定个参照：这周往哪几个方向走（最多 3 个），每个方向交出 1–2 样能检查的东西，每天给它多少时间。五分钟，选择题为主。",
                        style = MaterialTheme.typography.bodyLarge, color = sig.muted, modifier = Modifier.enter(1),
                    )
                    BigButton("进入计划模式", { nav.navigate("plan/new") }, Modifier.enter(2).testTag("plan-start"), color = sig.plan)
                    OutlinedButton(onClick = {
                        app.chatDraft = "进入计划模式：帮我起草这周的计划。先看看我手上的事，问我一两个选择题就起草。"
                        nav.navigate("chat")
                    }, modifier = Modifier.fillMaxWidth().enter(3)) { Text("让参谋起草，我只管点头") }
                    PlanWhy(Modifier.enter(4))
                }
                next?.let { n ->
                    SectionLabel("下周（${weekLabel(n.weekStart)}）已经立项")
                    Panel { PlanBody(n, showChecks = false) }
                }
                return@Column
            }

            // ---------- 生效中的计划 ----------
            val pr = remember(active, work, now) { Plans.progress(active, work, now, zone) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.enter(0)) {
                ProgressRing(if (active.outcomes.isEmpty()) 0f else active.doneCount.toFloat() / active.outcomes.size, sig.plan, size = 72.dp, stroke = 5.dp) {
                    Text("${active.doneCount}/${active.outcomes.size}", style = MaterialTheme.typography.titleMedium.merge(Mono), color = sig.plan)
                }
                Column(Modifier.weight(1f)) {
                    Text("本周 ${weekLabel(active.weekStart)}", style = MaterialTheme.typography.headlineSmall)
                    Text("第 ${pr.day} 天 · 交出 ${active.doneCount}/${active.outcomes.size}" + if (Plans.isRestDay(active, now, zone)) " · 今天休息" else "", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
                }
            }

            // 今天
            MainCard(active, now, nav, Modifier.enter(1))
            Panel(modifier = Modifier.enter(2)) {
                Text("今天的时间", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${pr.todayPlanMinutes}", style = MaterialTheme.typography.displaySmall.merge(Mono).copy(letterSpacing = 0.sp))
                    Text(" / ${active.dailyMinutes} 分钟给计划内的事", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                Bar(pr.todayPlanMinutes.toFloat() / active.dailyMinutes.coerceAtLeast(1), sig.plan)
                Hint("这周计划内 ${pr.planMinutes} 分钟，计划外 ${pr.offMinutes} 分钟。" + if (pr.offDone.isNotEmpty()) "做完的计划外：${pr.offDone.take(3).joinToString("、")}" else "")
            }

            // 方向和交出物
            SectionLabel("方向和交出物")
            active.goals.forEachIndexed { i, g ->
                val gp = pr.goals.getOrNull(i)
                Panel(modifier = Modifier.enter(3 + i)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).background(sig.plan, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Black)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(g.title, style = MaterialTheme.typography.titleMedium)
                            if (g.why.isNotBlank()) Text(g.why, style = MaterialTheme.typography.bodySmall, color = sig.muted)
                        }
                    }
                    gp?.let { Hint("这周做完 ${it.actionsDone} 步 · ${it.minutes} 分钟") }
                    active.outcomesOf(g.id).forEach { o -> OutcomeRow(active, o, nav, onDrop = { dropping = o }) }
                    Row {
                        TextButton(onClick = { adding = g }) { Text("＋ 加一样") }
                        if (g.threadKey.isNotBlank()) TextButton(onClick = { nav.navigate("thread/${g.threadKey}") }) { Text("看事项", color = sig.muted) }
                    }
                }
            }

            if (active.ifThens.isNotEmpty()) {
                SectionLabel("预演")
                Panel {
                    active.ifThens.forEach { Text("如果${it.trigger}，就${it.response}", style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nav.navigate("plan/new") }, modifier = Modifier.weight(1f)) { Text("重新定") }
                OutlinedButton(onClick = { nav.navigate("plan/close") }, modifier = Modifier.weight(1f).testTag("plan-close")) { Text("结束本周") }
            }
            if (active.revisions.isNotEmpty()) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "收起修改记录" else "改过 ${active.revisions.size} 次（不追责，只留痕）", color = sig.muted) }
                AnimatedVisibility(showLog, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        active.revisions.reversed().forEach { r -> Hint("${clock(r.at)} ${r.what} —— ${r.why}") }
                    }
                }
            }
        }
    }

    dropping?.let { o ->
        EditDialog("去掉「${o.title}」", "", hint = "写一句为什么（只留痕，不追责）", singleLine = true, onDismiss = { dropping = null }) { why ->
            active?.let { app.actions.plan.dropOutcome(it.id, o.id, why) }
        }
    }
    adding?.let { g ->
        var title by remember { mutableStateOf("") }
        var why by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("给「${g.title}」加一样") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("交出什么") }, singleLine = true)
                    OutlinedTextField(why, { why = it }, label = { Text("为什么加") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) active?.let { app.actions.plan.addOutcome(it.id, g.id, title.trim(), "", why) }
                    adding = null
                }) { Text("加") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlanWhy(modifier: Modifier) {
    val sig = LocalSignals.current
    Panel(modifier = modifier) {
        Text("为什么要一个计划", style = MaterialTheme.typography.titleSmall)
        Text("没有参照，每天都在“现在该干嘛”里打转。有了参照：", style = MaterialTheme.typography.bodyMedium)
        listOf(
            "首页默认推“今天的主线”，不用每次重新想",
            "做计划外的事不拦，只标一下，心里有数",
            "落后了，总控会收紧一手的放行，提醒你回到主线",
            "周末一眼看到：计划了什么、做了什么、时间花在哪",
        ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
    }
}

@Composable
fun Bar(p: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(LocalSignals.current.line, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(8.dp).background(color, RoundedCornerShape(50)))
    }
}

/** 计划的正文：方向、交出物、节奏、预演。 */
@Composable
fun PlanBody(p: WeekPlan, showChecks: Boolean) {
    val sig = LocalSignals.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        p.goals.forEachIndexed { i, g ->
            Text("${i + 1}. ${g.title}", style = MaterialTheme.typography.titleSmall)
            p.outcomesOf(g.id).forEach { o ->
                Text(
                    "    " + (if (showChecks && o.done) "✓ " else "· ") + o.title + (o.criteria.takeIf { it.isNotBlank() }?.let { "（做到：$it）" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            "每天 ${p.dailyMinutes} 分钟" + if (p.restDays.isNotEmpty()) " · 休息 " + p.restDays.sorted().joinToString("、") { "周" + "一二三四五六日"[it - 1] } else "",
            style = MaterialTheme.typography.bodySmall, color = sig.muted,
        )
        p.ifThens.forEach { Text("如果${it.trigger}，就${it.response}", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
    }
}

@Composable
private fun OutcomeRow(plan: WeekPlan, o: Outcome, nav: NavHostController, onDrop: () -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val main = app.store.mainKey()
    Row(
        Modifier.fillMaxWidth().clickable { app.actions.plan.setOutcomeDone(plan.id, o.id, !o.done) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(24.dp).background(if (o.done) sig.free else Color.Transparent, RoundedCornerShape(6.dp))
                .then(Modifier.background(Color.Transparent)),
            contentAlignment = Alignment.Center,
        ) {
            if (o.done) Text("✓", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Black)
            else Box(Modifier.size(20.dp).background(sig.line, RoundedCornerShape(5.dp)))
        }
        Column(Modifier.weight(1f)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge, color = if (o.done) sig.muted else MaterialTheme.colorScheme.onSurface)
            if (o.criteria.isNotBlank()) Text("做到：${o.criteria}", style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!o.done && o.actionKey.isNotBlank()) {
            if (main == o.actionKey) Tag("主线", sig.plan)
            else TextButton(onClick = { app.actions.plan.setMain(o.actionKey, o.id) }) { Text("当主线", style = MaterialTheme.typography.labelMedium) }
        }
        TextButton(onClick = onDrop) { Text("×", color = sig.muted) }
    }
}

/** 今天的主线：定了就显示它和“开始”，没定就从交出物里挑一个。 */
@Composable
private fun MainCard(plan: WeekPlan, now: Long, nav: NavHostController, modifier: Modifier) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val work by app.store.work.collectAsStateWithLifecycle()
    val book by app.store.plans.collectAsStateWithLifecycle()
    val main = remember(book, work, now) { Plans.mainAction(book, work, now, app.store.zone) }
    Panel(accent = sig.plan, modifier = modifier.testTag("plan-main")) {
        Text("今天的主线", style = MaterialTheme.typography.labelLarge, color = sig.plan, fontWeight = FontWeight.Bold)
        if (main != null) {
            Text(main.title, style = MaterialTheme.typography.headlineSmall)
            if (main[F.CRITERIA].isNotBlank()) Text("做到：${main[F.CRITERIA]}", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
            BigButton("开始", { nav.navigate("start/${main.key}") }, color = sig.plan)
        } else {
            val open = plan.outcomes.filter { !it.done && it.actionKey.isNotBlank() && work.rec(it.actionKey)?.actionStatus?.open == true }
            if (open.isEmpty()) {
                Hint("交出物都完成了，或者还没有对应的行动。今天可以休息，或者加一样。")
            } else {
                Hint("今天只推一样。挑一个：")
                open.take(4).forEach { o ->
                    ChoiceCard(o.title, plan.goal(o.goalId)?.title.orEmpty(), color = sig.plan) { app.actions.plan.setMain(o.actionKey, o.id) }
                }
            }
        }
    }
}

// ---------- 计划模式（向导） ----------

/**
 * 计划模式：一屏一个问题，选择为主。
 * 回看 → 方向（≤3）→ 交出什么（每个 ≤2）→ 节奏 → 预演 → 立项。每一步都存草稿，中途退出不丢。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanModeWizard(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val zone = app.store.zone
    val now0 = remember { System.currentTimeMillis() }
    val work = app.store.work.value
    val book = app.store.plans.value
    // 周日定的是下周；其他日子定本周
    val sunday = DayClock.weekday(now0, zone) == 7
    val ws = remember { Plans.weekStart(now0, zone).let { if (sunday) it.plusWeeks(1) else it }.toString() }
    val existing = remember { book.weeks.lastOrNull { it.weekStart == ws && it.status == PlanStatus.DRAFT } ?: book.weeks.lastOrNull { it.weekStart == ws && it.status == PlanStatus.ACTIVE } }
    val prev = remember { Plans.previous(book, ws) }
    val leftovers = remember { Plans.leftovers(prev) }
    val candidates = remember { Plans.goalCandidates(work) }

    val goals = remember { mutableStateListOf<Goal>().apply { existing?.goals?.let { addAll(it) } } }
    val outcomes = remember { mutableStateListOf<Outcome>().apply { existing?.outcomes?.let { addAll(it) } } }
    val ifThens = remember { mutableStateListOf<IfThen>().apply { existing?.ifThens?.let { addAll(it) } } }
    var daily by remember { mutableIntStateOf(existing?.dailyMinutes ?: 90) }
    var rest by remember { mutableStateOf(existing?.restDays ?: setOf(7)) }
    var step by remember { mutableIntStateOf(0) }
    var newGoal by remember { mutableStateOf("") }
    val planId = remember { existing?.takeIf { it.status == PlanStatus.DRAFT }?.id ?: Plans.newId("w", now0, 0) }
    val total = 6

    // 第一次进来、上周有没做完的：方向和交出物先带上
    remember {
        if (existing == null && prev != null) {
            leftovers.forEach { o ->
                val g = prev.goal(o.goalId) ?: return@forEach
                if (goals.none { it.id == g.id } && goals.size < Plans.MAX_GOALS) goals += g
                if (goals.any { it.id == g.id }) outcomes += o.copy(done = false)
            }
        }
        true
    }

    fun build(): WeekPlan = WeekPlan(
        id = planId, weekStart = ws, status = PlanStatus.DRAFT, goals = goals.toList(),
        outcomes = outcomes.filter { o -> goals.any { it.id == o.goalId } },
        dailyMinutes = daily, restDays = rest, ifThens = ifThens.toList(), by = "你", createdAt = now0,
    )
    fun save() = app.actions.plan.saveDraft(build())
    fun go(s: Int) { save(); step = s }

    val last = remember { intArrayOf(step) }
    val forward = step >= last[0]
    last[0] = step

    Column(Modifier.fillMaxSize()) {
        BackBar(if (sunday) "计划模式 · 下周" else "计划模式 · 本周", { if (step == 0 || step == total - 1) nav.popBackStack() else step-- })
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { i ->
                val w by animateDpAsState(if (i == step) 26.dp else 8.dp, label = "pd$i")
                Box(Modifier.width(w).height(8.dp).background(if (i <= step) sig.plan else sig.line, RoundedCornerShape(50)))
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (forward) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            },
            label = "planwiz",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (s) {
                    0 -> {
                        Text("先看一眼", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                        if (prev == null) {
                            Text("第一次做计划。不难：选方向、选交出什么、给多少时间、想想会在哪卡住，然后点头。都是选择题。", style = MaterialTheme.typography.bodyLarge, color = sig.muted, modifier = Modifier.enter(1))
                        } else {
                            Panel(modifier = Modifier.enter(1)) {
                                Text("上一份计划（${weekLabel(prev.weekStart)}）交出 ${prev.doneCount}/${prev.outcomes.size}", style = MaterialTheme.typography.titleSmall)
                                if (prev.review.isNotBlank()) Hint("你当时说：${prev.review}")
                                if (leftovers.isNotEmpty()) {
                                    Text("没做完的，先带过来了（不想要可以去掉）：", style = MaterialTheme.typography.bodyMedium)
                                    leftovers.forEach { Text("· ${it.title}", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
                                }
                            }
                        }
                        val recent = remember {
                            work.actions().filter { (it.actionStatus == com.zongkong.core.work.ActionStatus.DONE || it.actionStatus == com.zongkong.core.work.ActionStatus.CONFIRM) && now0 - it.updatedAt < 7 * 86_400_000L }
                        }
                        if (recent.isNotEmpty()) {
                            Panel(modifier = Modifier.enter(2)) {
                                Text("最近 7 天做完了 ${recent.size} 步", style = MaterialTheme.typography.titleSmall)
                                recent.take(5).forEach { Text("· ${it.title}", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
                            }
                        }
                        BigButton("开始定", { go(1) }, Modifier.enter(3).testTag("pm-begin"), color = sig.plan)
                        TextButton(onClick = {
                            app.chatDraft = "进入计划模式：帮我起草${if (sunday) "下周" else "这周"}的计划。"
                            nav.navigate("chat")
                        }) { Text("不想自己选？让参谋起草", color = sig.ai) }
                    }
                    1 -> {
                        Text("这周往哪几个方向走？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                        Text("最多 3 个。少比多好：选一个也行。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
                        goals.filter { g -> candidates.none { it.key == g.threadKey } }.forEach { g ->
                            ChoiceCard(g.title, g.why.ifBlank { "已选" }, selected = true, color = sig.plan) {
                                goals.remove(g)
                                outcomes.removeAll { it.goalId == g.id }
                            }
                        }
                        candidates.forEachIndexed { i, t ->
                            val sel = goals.any { it.threadKey == t.key }
                            ChoiceCard(t.title, t[F.WHY].ifBlank { t.threadStatus.label }, selected = sel, color = sig.plan, modifier = Modifier.enter(2 + i).testTag("pm-goal-$i")) {
                                if (sel) {
                                    val g = goals.first { it.threadKey == t.key }
                                    goals.remove(g)
                                    outcomes.removeAll { it.goalId == g.id }
                                } else if (goals.size < Plans.MAX_GOALS) {
                                    goals += Goal(Plans.newId("g", System.currentTimeMillis(), goals.size), t.title, t[F.WHY], t.key)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(newGoal, { newGoal = it }, label = { Text("新的方向") }, singleLine = true, modifier = Modifier.weight(1f).testTag("pm-new-goal"))
                            TextButton(onClick = {
                                if (newGoal.isNotBlank() && goals.size < Plans.MAX_GOALS) {
                                    goals += Goal(Plans.newId("g", System.currentTimeMillis(), goals.size), newGoal.trim())
                                    newGoal = ""
                                }
                            }, modifier = Modifier.testTag("pm-add-goal")) { Text("加") }
                        }
                        if (goals.size >= Plans.MAX_GOALS) Hint("已经 3 个了。再多就不是方向，是愿望清单。")
                        BigButton("下一步（${goals.size}/3）", { go(2) }, enabled = goals.isNotEmpty(), color = sig.plan, modifier = Modifier.testTag("pm-next"))
                    }
                    2 -> {
                        Text("每个方向，这周交出什么？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                        Text("每个方向 1–2 样，要能检查：做完了就能给人看。点建议就行。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
                        goals.forEachIndexed { gi, g ->
                            Panel(modifier = Modifier.enter(2 + gi)) {
                                Text(g.title, style = MaterialTheme.typography.titleSmall)
                                val mine = outcomes.filter { it.goalId == g.id }
                                mine.forEach { o ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("· ${o.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { outcomes.remove(o) }) { Text("×", color = sig.muted) }
                                    }
                                }
                                if (mine.size < Plans.MAX_OUTCOMES_PER_GOAL) {
                                    val sugg = remember(g.threadKey) { if (g.threadKey.isBlank()) emptyList() else Plans.outcomeCandidates(work, g.threadKey) }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        sugg.filter { s2 -> mine.none { it.title == s2 } }.forEach { s2 ->
                                            QuickChip(s2.take(18), sig.plan) {
                                                outcomes += Outcome(Plans.newId("o", System.currentTimeMillis(), outcomes.size), g.id, s2, threadKey = g.threadKey)
                                            }
                                        }
                                    }
                                    var custom by remember(g.id) { mutableStateOf("") }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(custom, { custom = it }, label = { Text("或者自己写") }, singleLine = true, modifier = Modifier.weight(1f).testTag("pm-outcome-$gi"))
                                        TextButton(onClick = {
                                            if (custom.isNotBlank()) {
                                                outcomes += Outcome(Plans.newId("o", System.currentTimeMillis(), outcomes.size), g.id, custom.trim(), threadKey = g.threadKey)
                                                custom = ""
                                            }
                                        }, modifier = Modifier.testTag("pm-add-outcome-$gi")) { Text("加") }
                                    }
                                }
                            }
                        }
                        val ready = goals.all { g -> outcomes.any { it.goalId == g.id } }
                        if (!ready) Hint("每个方向至少一样。")
                        BigButton("下一步", { go(3) }, enabled = ready, color = sig.plan, modifier = Modifier.testTag("pm-next"))
                    }
                    3 -> {
                        Text("每天给它多少时间？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                        Text("说的是计划内的事，不是一整天。宁少勿多：做到了下周再加。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(45, 60, 90, 120, 180, 240).forEach { m -> PlanPill(if (m < 60) "$m 分钟" else "${m / 60}${if (m % 60 == 30) ".5" else ""} 小时", daily == m, sig.plan) { daily = m } }
                        }
                        Text("哪天休息", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..7).forEach { d -> PlanPill("周" + "一二三四五六日"[d - 1], d in rest, sig.plan) { rest = if (d in rest) rest - d else rest + d } }
                        }
                        Hint("休息日总控不会因为计划收紧一手；节律关卡照常。")
                        BigButton("下一步", { go(4) }, color = sig.plan, modifier = Modifier.testTag("pm-next"))
                    }
                    4 -> {
                        Text("这周最可能在哪卡住？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                        Text("选 1–2 个，配一个对策。到时候参谋和总控会按这个提醒你。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
                        Plans.COMMON_TRAPS.forEachIndexed { i, (trap, responses) ->
                            val chosen = ifThens.firstOrNull { it.trigger == trap }
                            ChoiceCard(trap, chosen?.let { "就：${it.response}" } ?: "", selected = chosen != null, color = sig.plan, modifier = Modifier.enter(2 + i)) {
                                if (chosen != null) ifThens.remove(chosen) else if (ifThens.size < 3) ifThens += IfThen(trap, responses.first())
                            }
                            if (chosen != null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
                                    responses.forEach { r -> PlanPill(r, chosen.response == r, sig.plan) { ifThens[ifThens.indexOf(chosen)] = chosen.copy(response = r) } }
                                }
                            }
                        }
                        BigButton(if (ifThens.isEmpty()) "跳过" else "下一步", { go(5) }, color = sig.plan, modifier = Modifier.testTag("pm-next"))
                    }
                    else -> {
                        val p = build()
                        var approved by remember { mutableStateOf(false) }
                        if (!approved) {
                            Text("就这样？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                            Text("这是参照，不是合同。随时能改，改的时候写一句为什么。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
                            Panel(modifier = Modifier.enter(2)) { PlanBody(p, showChecks = false) }
                            BigButton("立项", {
                                app.actions.plan.saveDraft(p)
                                app.actions.plan.approve(p.id)
                                approved = true
                            }, Modifier.enter(3).testTag("pm-approve"), color = sig.plan)
                        } else {
                            Spacer(Modifier.height(24.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("立项", sig.plan, size = 132.dp) }
                            Text("${if (sunday) "下周" else "本周"}有参照了", style = MaterialTheme.typography.headlineSmall)
                            Hint("每样交出物都在事项下建了一步。首页会推今天的主线；落后了总控会收紧一手的放行。")
                            if (!sunday) BigButton("定今天的主线", { nav.navigate("plan") { popUpTo("home") } }, color = sig.plan)
                            OutlinedButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("回到今天") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanPill(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) color else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color else LocalSignals.current.line),
        onClick = onClick,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

// ---------- 结束本周 ----------

/** 结项：计划了什么、做了什么、时间花在哪；一句话；没做完的带到下一份。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanCloseScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val now = remember { System.currentTimeMillis() }
    val plan = remember { app.actions.plan.active() }
    var review by remember { mutableStateOf("") }
    var closed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar("结束本周", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (plan == null) {
                Hint("没有生效中的计划。")
                return@Column
            }
            val pr = remember { Plans.progress(plan, app.store.work.value, now, app.store.zone) }
            if (closed) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("结项", sig.hq, size = 132.dp) }
                Text("这一份结了", style = MaterialTheme.typography.headlineSmall)
                Hint("没做完的 ${plan.outcomes.count { !it.done }} 样，下一份计划会先带上。")
                BigButton("现在定下一份", { nav.navigate("plan/new") { popUpTo("plan") } }, color = sig.plan)
                OutlinedButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("回到今天") }
                return@Column
            }
            Text("这周怎么样", style = MaterialTheme.typography.headlineMedium)
            Panel {
                Text("交出 ${plan.doneCount}/${plan.outcomes.size}", style = MaterialTheme.typography.titleMedium)
                plan.outcomes.forEach { Text((if (it.done) "✓ " else "· ") + it.title, style = MaterialTheme.typography.bodyMedium, color = if (it.done) MaterialTheme.colorScheme.onSurface else sig.muted) }
            }
            Panel {
                Text("时间花在哪", style = MaterialTheme.typography.titleSmall)
                pr.goals.forEach { g -> Text("${g.goal.title}：${g.minutes} 分钟，做完 ${g.actionsDone} 步", style = MaterialTheme.typography.bodyMedium) }
                Text("计划外：${pr.offMinutes} 分钟", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
                Hint("预算是每天 ${plan.dailyMinutes} 分钟。")
            }
            OutlinedTextField(review, { review = it }, label = { Text("一句话：哪里比预想顺 / 哪里卡了") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("方向太多", "时间给多了", "时间给少了", "总被打断", "比预想顺", "主线不清楚").forEach { c -> QuickChip(c, sig.hq) { review = if (review.isBlank()) c else "$review；$c" } }
            }
            BigButton("结项", { app.actions.plan.close(plan.id, review.trim()); closed = true }, color = sig.hq)
        }
    }
}
