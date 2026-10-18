package com.zongkong.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zongkong.app.data.Actions
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Rec
import com.zongkong.core.work.StuckReason
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/*
 * 向导：一屏只问一件事，能点就不用打字。给“不想想、只想有人带着走”的时候用。
 */

/** 顶部的步骤点：当前这一步拉长。 */
@Composable
private fun StepDots(step: Int, total: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val w by animateDpAsState(if (i == step) 26.dp else 8.dp, label = "dot$i")
            Box(Modifier.width(w).height(8.dp).background(if (i <= step) color else LocalSignals.current.line, RoundedCornerShape(50)))
        }
    }
}

/** 向导的一屏：大问题 + 一行说明 + 内容。左右滑入。 */
@Composable
private fun WizardFrame(
    title: String,
    step: Int,
    total: Int,
    color: Color,
    onBack: () -> Unit,
    content: @Composable ColumnScope.(Int) -> Unit,
) {
    // 记上一步（普通数组，不触发重组），用来判断往前还是往回翻
    val last = remember { intArrayOf(step) }
    val forward = step >= last[0]
    last[0] = step
    Column(Modifier.fillMaxSize()) {
        BackBar(title, onBack)
        Box(Modifier.padding(horizontal = 20.dp)) { StepDots(step, total, color) }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (forward) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            },
            label = "wizard",
            modifier = Modifier.fillMaxSize(),
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content(s) }
        }
    }
}

@Composable
private fun Question(q: String, hint: String = "") {
    Text(q, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
    if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted, modifier = Modifier.enter(1))
    Spacer(Modifier.height(4.dp))
}

/** 时间选项：(显示, 交给 TimeParse 的写法)。 */
private fun timeChoices(now: Long, zone: ZoneId, minutes: Int): List<Pair<String, String>> {
    val t = Instant.ofEpochMilli(now).atZone(zone)
    fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)
    fun span(prefix: String, h: Int, m: Int): String {
        val end = (h * 60 + m + minutes).coerceAtMost(23 * 60 + 59)
        return "$prefix ${hm(h, m)}-${hm(end / 60, end % 60)}"
    }
    val out = mutableListOf<Pair<String, String>>()
    // 现在：取整到下一个 5 分钟
    val nowMin = (t.hour * 60 + t.minute + 4) / 5 * 5
    if (nowMin + minutes < 24 * 60) out += "现在" to span("今天", nowMin / 60, nowMin % 60)
    val half = nowMin + 30
    if (half + minutes < 24 * 60) out += "半小时后" to span("今天", half / 60, half % 60)
    listOf(9 to 0, 14 to 0, 16 to 0, 20 to 0).filter { (h, m) -> h * 60 + m > nowMin + 30 }.forEach { (h, m) -> out += "今天 ${hm(h, m)}" to span("今天", h, m) }
    out += "明早 9:00" to span("明天", 9, 0)
    out += "明晚 20:00" to span("明天", 20, 0)
    return out
}

// ---------- 晨间部署 ----------

/**
 * 晨间部署：今天最要紧的一件 → 第一步做什么 → 几点做 → 做到什么算完 → 盖章。
 * 排好的行动就是“今日部署”关卡的证据，验收自动打勾。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanWizard(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val work = app.store.work.value
    val zone = app.store.zone
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var step by remember { mutableIntStateOf(0) }
    var threadKey by remember { mutableStateOf("") }
    var newThread by remember { mutableStateOf("") }
    var existing by remember { mutableStateOf<Rec?>(null) }
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(45) }
    var time by remember { mutableStateOf("") }
    var customTime by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var doneKey by remember { mutableStateOf("") }
    var passed by remember { mutableStateOf<List<String>>(emptyList()) }
    val threads = remember { work.threads().filter { it.threadStatus.open }.sortedWith(compareByDescending<Rec> { it[F.PRIORITY] == "高" }.thenByDescending { it.updatedAt }).take(6) }
    val weekPlan = remember { app.actions.plan.active() }
    var outcomeId by remember { mutableStateOf("") }
    val total = 5

    WizardFrame("晨间部署", step, total, sig.plan, { if (step == 0 || step == 4) nav.popBackStack() else step-- }) { s ->
        when (s) {
            0 -> {
                Question("今天最要紧的一件事是？", if (weekPlan != null) "从本周计划里挑一样当今天的主线。其他的今天不管也没关系。" else "只挑一件。其他的今天不管也没关系。")
                val planOpen = weekPlan?.outcomes?.filter { !it.done }.orEmpty()
                if (planOpen.isNotEmpty()) {
                    Text("按本周计划", style = MaterialTheme.typography.labelLarge, color = sig.plan)
                    planOpen.forEachIndexed { i, o ->
                        ChoiceCard(o.title, weekPlan?.goal(o.goalId)?.title.orEmpty(), selected = outcomeId == o.id, color = sig.plan, modifier = Modifier.enter(2 + i).testTag("wiz-outcome-$i")) {
                            outcomeId = o.id
                            threadKey = o.threadKey
                            newThread = ""
                            existing = work.rec(o.actionKey)?.takeIf { it.actionStatus.open }
                            title = existing?.title ?: o.title
                            criteria = existing?.get(F.CRITERIA)?.ifBlank { null } ?: o.criteria
                            step = 1
                        }
                    }
                    Text("计划外的事", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                }
                threads.forEachIndexed { i, t ->
                    ChoiceCard(t.title, t[F.NEXT].ifBlank { t.threadStatus.label }.let { "下一步：$it" }, selected = threadKey == t.key, color = sig.plan, modifier = Modifier.enter(2 + i)) {
                        threadKey = t.key
                        newThread = ""
                        outcomeId = weekPlan?.outcomes?.firstOrNull { it.threadKey == t.key && !it.done }?.id.orEmpty()
                        val open = work.actionsOf(t.key).filter { it.actionStatus.open }
                        existing = open.firstOrNull { it.plan == null } ?: open.firstOrNull()
                        title = existing?.title ?: t[F.NEXT]
                        criteria = existing?.get(F.CRITERIA)?.ifBlank { null } ?: t[F.CRITERIA]
                        step = 1
                    }
                }
                OutlinedTextField(
                    newThread, { newThread = it; threadKey = "" }, label = { Text(if (threads.isEmpty()) "写一件今天要推进的事" else "或者，新的一件事") },
                    singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide() }), modifier = Modifier.fillMaxWidth().testTag("wiz-new-thread"),
                )
                if (newThread.isNotBlank()) BigButton("下一步", {
                    existing = null; title = ""; criteria = ""; step = 1
                }, color = sig.plan)
                TextButton(onClick = { app.chatDraft = "帮我挑一下今天最要紧的一件事，并排好第一步。"; nav.navigate("chat") }) { Text("拿不准？让总控帮你挑", color = sig.ai) }
            }
            1 -> {
                Question("第一步做什么？", "要具体到坐下就能开始。动词开头：打开、写、列、读、打电话……")
                existing?.let { e -> Hint("这件事里已经有一步没做：「${e.title}」，可以直接用。") }
                OutlinedTextField(title, { title = it }, label = { Text("第一步") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide() }), modifier = Modifier.fillMaxWidth().testTag("wiz-title"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("打开文档写 10 分钟", "列出 3 个要点", "读完一节并标重点", "做 5 道题").forEach { QuickChip(it, sig.plan) { title = it } }
                }
                BigButton("下一步", { if (title.isNotBlank()) step = 2 }, enabled = title.isNotBlank(), color = sig.plan)
            }
            2 -> {
                Question("什么时候做？", "排进时间，到点总控会提醒你；15 分钟没开始再追问一次。")
                Text("做多久", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 45, 60, 90).forEach { m -> ChoicePill("$m 分钟", minutes == m, sig.plan) { minutes = m } }
                }
                Text("从几点", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                val choices = remember(minutes) { timeChoices(System.currentTimeMillis(), zone, minutes) }
                choices.forEachIndexed { i, (label, value) ->
                    ChoiceCard(label, value, selected = time == value, color = sig.plan, modifier = Modifier.enter(i)) { time = value; customTime = ""; step = 3 }
                }
                OutlinedTextField(customTime, { customTime = it }, label = { Text("或者自己写：周六 10:00-11:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (customTime.isNotBlank()) BigButton("用这个时间", {
                    if (TimeParse.parse(customTime, System.currentTimeMillis(), zone) == null) error = "“$customTime”没认出来。换个写法：今晚 20:00-21:00、明天 9:00"
                    else { time = customTime; error = null; step = 3 }
                }, color = sig.plan)
                error?.let { Text(it, color = sig.strict, style = MaterialTheme.typography.bodySmall) }
            }
            3 -> {
                Question("做到什么算完？", "要能检查。“写完”不算，“写完第二段并保存”算。")
                OutlinedTextField(criteria, { criteria = it }, label = { Text("完成依据") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide() }), modifier = Modifier.fillMaxWidth().testTag("wiz-criteria"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("写完并保存", "列出 3 条", "做完并对答案", "发出去").forEach { c -> QuickChip(c, sig.plan) { criteria = if (criteria.isBlank()) c else "$criteria，$c" } }
                }
                BigButton("排好", {
                    var tk = threadKey
                    if (tk.isBlank()) tk = app.actions.work.createThread(newThread.trim(), next = title.trim(), criteria = "")
                    val e = existing
                    if (e != null && e.title == title.trim()) {
                        app.actions.work.reschedule(e.key, time)
                        if (criteria.trim() != e[F.CRITERIA]) app.actions.work.update(e.key, mapOf(F.CRITERIA to criteria.trim()))
                        doneKey = e.key
                    } else {
                        val (k, err) = app.actions.work.addAction(tk, title.trim(), time, criteria.trim())
                        if (k == null) { error = err; return@BigButton }
                        doneKey = k
                    }
                    if (tk.isNotBlank()) app.actions.work.update(tk, mapOf(F.STATUS to ThreadStatus.ACTIVE.label))
                    // 第一件排好的（或者从计划里挑的）就是今天的主线
                    if (outcomeId.isNotBlank() || app.store.mainKey().isBlank()) app.actions.plan.setMain(doneKey, outcomeId)
                    scope.launch { passed = runCatching { app.actions.autoVerify(confirmPlan = true) }.getOrDefault(emptyList()) }
                    step = 4
                }, enabled = criteria.isNotBlank(), color = sig.plan, modifier = Modifier.testTag("wiz-save"))
                error?.let { Text(it, color = sig.strict, style = MaterialTheme.typography.bodySmall) }
            }
            else -> {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp("已部署", sig.plan, size = 132.dp) }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.enter(1))
                Text(TimeParse.parse(time, System.currentTimeMillis(), zone)?.let { TimeParse.label(it, System.currentTimeMillis(), zone) } ?: time, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = sig.muted)
                Text("做到：$criteria", style = MaterialTheme.typography.bodyMedium)
                if (passed.isNotEmpty()) Pill("${passed.joinToString("、")} 已自动验收", sig.free)
                BigButton("现在就开始", { nav.navigate("start/$doneKey") { popUpTo("home") } }, color = sig.plan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        threadKey = ""; newThread = ""; existing = null; title = ""; time = ""; criteria = ""; step = 0
                    }, modifier = Modifier.weight(1f)) { Text("再排一件") }
                    OutlinedButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, modifier = Modifier.weight(1f)) { Text("回到今天") }
                }
            }
        }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) color else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color else LocalSignals.current.line),
        onClick = onClick,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

// ---------- 晚间复盘 ----------


// ---------- 我卡住了（先选是哪一步） ----------

/** 首页“我卡住了”：先确认是哪一步，再按原因给办法。 */
@Composable
fun StuckPick(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val work = app.store.work.value
    val cur = remember { com.zongkong.core.work.Focus.current(work, System.currentTimeMillis(), app.store.zone, app.store.mainKey()) }
    val open = remember { work.actions().filter { it.actionStatus.open }.sortedByDescending { it.updatedAt }.take(6) }
    Column(Modifier.fillMaxSize()) {
        BackBar("我卡住了", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(110.dp)) { Vortex(LocalSignals.current.strict, Modifier.fillMaxSize(), intensity = 1.2f) }
            Question("卡在哪一步？", "选一步，接下来按卡住的原因给你办法。")
            cur.action?.let { a -> ChoiceCard(a.title, "当前这一步 · ${cur.why.label}", color = sig.strict, modifier = Modifier.enter(2)) { nav.navigate("stuck/${a.key}") } }
            open.filter { it.key != cur.action?.key }.forEachIndexed { i, a ->
                ChoiceCard(a.title, work.rec(a.threadKey)?.title.orEmpty(), color = sig.strict, modifier = Modifier.enter(3 + i)) { nav.navigate("stuck/${a.key}") }
            }
            if (open.isEmpty()) {
                Hint("没有待做的行动。不是卡在某一步，而是不知道该干什么？")
                BigButton("跟着向导排一件", { nav.navigate("wizard/plan") })
            }
            TextButton(onClick = { app.chatDraft = "我卡住了，不知道从哪下手。"; nav.navigate("chat") }) { Text("说不清？跟总控聊聊", color = sig.ai) }
        }
    }
}
