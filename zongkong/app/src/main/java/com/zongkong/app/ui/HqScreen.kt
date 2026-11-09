package com.zongkong.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.link.YishouClient
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.Defaults
import com.zongkong.core.Policy
import com.zongkong.core.court.Court
import com.zongkong.core.court.Slot
import com.zongkong.core.court.Verdict
import com.zongkong.core.YishouStatus
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqSession
import com.zongkong.core.hq.HqStep
import com.zongkong.core.hq.HqTrigger
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.Capture
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Rec
import com.zongkong.core.work.ThreadStatus
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** 夜空上的一张纸：放要读、要写的东西。 */
@Composable
private fun Paper(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PaperTheme {
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFFFBF8F2), contentColor = Color(0xFF1F1B16)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** 夜空上的一块玻璃：放数字和状态。 */
@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Night.card, RoundedCornerShape(18.dp)).border(BorderStroke(1.dp, Night.line), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
private fun NightChip(label: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) Night.glow else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Night.glow else Night.line),
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(label, color = if (selected) Night.top else Night.ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/**
 * 总控时间。没在进行时：入口（现在进入、设置、最近几次）。进行中：六个环节，夜空，一棵每修完一个部门就长一截的树。
 */
@Composable
fun HqScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val book by app.store.hq.collectAsStateWithLifecycle()
    val session = book.active
    var finished by remember { mutableStateOf<String?>(null) }
    // 封存会移除 HqRun，验收协程必须由仍在显示的总控页持有。
    val finishScope = rememberCoroutineScope()
    // 总控时间里按返回键不离开（做完才解锁）
    BackHandler(enabled = session != null) {}
    NightSky(Modifier.fillMaxSize().testTag("hq-screen")) {
        when {
            finished != null -> HqDone(finished!!, nav)
            session != null -> HqRun(session, nav) { summary ->
                finishScope.launch { finished = app.actions.hq.finish(summary) }
            }
            else -> HqEntry(nav, book.sessions)
        }
    }
}

@Composable
private fun HqEntry(nav: NavHostController, sessions: List<HqSession>) {
    val app = LocalContext.current.zk
    val cfg = app.store.config.collectAsStateWithLifecycle().value.hq
    val now = rememberNow(30_000)
    val due = remember(cfg, sessions, now) { Hq.due(cfg, sessions, now, app.store.zone) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.popBackStack() }) { Text("←", color = Night.dim, fontSize = 22.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { nav.navigate("settings/hq") }) { Text("设置", color = Night.dim) }
        }
        BranchGrowth(0.42f, Night.glow, Modifier.fillMaxWidth().height(170.dp), seed = 3)
        Text("总控时间", style = MaterialTheme.typography.displayMedium, color = Night.ink, modifier = Modifier.enter(0))
        Text(
            "每天 ${DayClock.hhmm(cfg.at)}，${cfg.minutes} 分钟。手机只剩总控和几个白名单应用，其他的打开就回到这里。" +
                "这是每天的公审：查账 → 分流 → 对底线 → 防瘫痪 → 三层矫正 → 签执行令。",
            style = MaterialTheme.typography.bodyLarge, color = Night.dim, modifier = Modifier.enter(1),
        )
        if (due == Hq.Due.SOON || due == Hq.Due.NOW || due == Hq.Due.OVERDUE) {
            Text(if (due == Hq.Due.SOON) "马上就到点了" else "已经到点了", color = Night.glow, style = MaterialTheme.typography.titleMedium)
        }
        Button(
            onClick = { app.actions.hq.start(HqTrigger.MANUAL) },
            modifier = Modifier.fillMaxWidth().height(58.dp).enter(2).testTag("hq-start"),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Night.glow, contentColor = Night.top),
        ) { Text("现在进入 · ${cfg.minutes} 分钟", style = MaterialTheme.typography.titleMedium) }
        Glass(Modifier.enter(3)) {
            Text("进入以后", color = Night.ink, style = MaterialTheme.typography.titleSmall)
            listOf(
                "只能用：总控、电话、" + (cfg.allowed.map { com.zongkong.app.data.AppNames.label(app, it) }.joinToString("、").ifBlank { "（没加别的）" }),
                "六个环节是一条流水线：上一环没过，下一环不放行",
                "没有退出按钮：走完六个环节、签下执行令，才解锁",
                "电话、紧急呼叫、闹钟一直能用",
            ).forEach { Text("· $it", color = Night.dim, style = MaterialTheme.typography.bodySmall) }
        }
        val recent = sessions.filterNot { it.active }.takeLast(5).reversed()
        if (recent.isNotEmpty()) {
            Text("最近几次", color = Night.dim, style = MaterialTheme.typography.labelLarge)
            recent.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(java.time.Instant.ofEpochMilli(s.startedAt).atZone(app.store.zone).let { "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }, color = Night.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(if (s.early) "提前退出" else "封存", color = if (s.early) Color(0xFFFF8A73) else Night.glow, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun HqDone(result: String, nav: NavHostController) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        BranchGrowth(1f, Night.glow, Modifier.fillMaxWidth().height(220.dp), seed = 7, durationMillis = 1200)
        Stamp("已修缮", Night.glow, size = 120.dp)
        Text(result, color = Night.ink, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Text("手机解锁了。明天同一时间见。", color = Night.dim, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Night.glow, contentColor = Night.top),
        ) { Text("回到今天") }
    }
}

@Composable
private fun HqRun(s: HqSession, nav: NavHostController, onFinish: (String) -> Unit) {
    val app = LocalContext.current.zk
    val now = rememberNow(1_000)
    var stepDone by remember(s.step) { mutableStateOf(false) }
    var stepNote by remember(s.step) { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    // 封：《强制执行令》，签了才能封存
    var order by remember(s.id) { mutableStateOf("") }
    var orderDue by remember(s.id) { mutableStateOf(0L) }
    val readyIn = Hq.stepReadyAt(s) - now
    val steps = HqStep.entries
    val remain = mmss(s.endsAt - now)
    // 秒数每变一次轻轻弹一下（1.02 → 1），扫一眼就知道在走
    val remainPop = rememberTickPop(remain)

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------- 顶部：环节、剩余时间 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("总控时间", color = Night.dim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "还剩 " + remain, color = Night.ink, style = MaterialTheme.typography.titleMedium.merge(Mono),
                modifier = Modifier.graphicsLayer { scaleX = remainPop; scaleY = remainPop }.testTag("hq-remaining"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            steps.forEachIndexed { i, st ->
                val done = i < s.step
                val cur = i == s.step
                // 做完的印章淡成暖色，正在做的那一格轻轻呼吸
                val bg by animateColorAsState(if (done) Night.glow else Color.Transparent, tween(320), label = "hqSealBg")
                val borderColor by animateColorAsState(if (done || cur) Night.glow else Night.line, tween(320), label = "hqSealBorder")
                Box(
                    Modifier.size(34.dp)
                        .then(if (cur) Modifier.breathe(0.06f, 1500) else Modifier)
                        .background(bg, RoundedCornerShape(8.dp))
                        .border(BorderStroke(if (cur) 2.dp else 1.dp, borderColor), RoundedCornerShape(8.dp))
                        .clickable(enabled = done && s.current == HqStep.SEAL) { app.actions.hq.revisit(st) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(st.seal, color = if (done) Night.top else if (cur) Night.glow else Night.dim, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
        }
        // ---------- 树：每修完一个部门长一截 ----------
        BranchGrowth((s.step + if (stepDone) 0.9f else 0.35f) / steps.size, Night.glow, Modifier.fillMaxWidth().height(96.dp), seed = 7)
        AnimatedContent(targetState = s.current, transitionSpec = { (fadeIn() + slideInVertically { it / 6 }) togetherWith fadeOut() }, label = "hqtitle") { st ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${st.seal} · ${st.title}", style = MaterialTheme.typography.headlineMedium, color = Night.ink, modifier = Modifier.testTag("hq-step"))
                Text(st.hint, style = MaterialTheme.typography.bodyMedium, color = Night.dim)
            }
        }

        // ---------- 这一环节：换环节时淡入 + 轻微下滑，不硬切 ----------
        AnimatedContent(
            targetState = s.current,
            transitionSpec = { (fadeIn(tween(260)) + slideInVertically { it / 12 }) togetherWith fadeOut(tween(160)) },
            label = "hqStepBody",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { step ->
            Box(Modifier.fillMaxSize()) {
                when (step) {
                    HqStep.SETTLE -> SettleStep({ stepDone = it }, { stepNote = it })
                    HqStep.INFO -> TriageStep(s, { stepDone = it }, { stepNote = it })
                    HqStep.THINK -> ThinkStep(s, { stepDone = it }, { stepNote = it })
                    HqStep.PLAN -> TomorrowStep({ stepDone = it }, { stepNote = it }, nav)
                    HqStep.ACT -> LedgerStep({ stepDone = it }, { stepNote = it })
                    HqStep.SEAL -> SealStep(s, now, { stepNote = it }, order, { order = it }, orderDue, { orderDue = it })
                }
            }
        }

        // ---------- 底部 ----------
        if (s.current == HqStep.SEAL) {
            val orderOk = order.trim().length >= 6 && orderDue > 0
            val can = Hq.canFinish(s, now) && orderOk
            val btnColor by animateColorAsState(if (can && !finishing) Night.glow else Night.card, tween(260), label = "hqBtn")
            val btnText by animateColorAsState(if (can && !finishing) Night.top else Night.dim, tween(260), label = "hqBtnText")
            Button(
                onClick = {
                    if (finishing) return@Button
                    finishing = true
                    val t = System.currentTimeMillis()
                    app.store.updateCourt { Court.issue(it, "o" + t.toString(36), order, orderDue, t) }
                    onFinish(stepNote.trim() + "\n执行令：${order.trim()}（时限 ${whenText(orderDue, app.store.zone)}）")
                },
                enabled = can && !finishing,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("hq-seal"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor, contentColor = btnText, disabledContainerColor = btnColor, disabledContentColor = btnText),
            ) { Text(if (finishing) "封存中…" else if (!orderOk) "先签执行令：一个具体动作 + 一个时限" else if (can) "签发执行令，封存，解锁" else if (s.endsAt > now) "还有 ${mmss(s.endsAt - now)}：点上面的印章回去再修一轮" else "读一眼总结，${mmss(Hq.finishAt(s) - now)} 后就能封存") }
        } else {
            val timeOk = readyIn <= 0
            val btnColor by animateColorAsState(if (timeOk && stepDone) Night.glow else Night.card, tween(260), label = "hqNext")
            val btnText by animateColorAsState(if (timeOk && stepDone) Night.top else Night.dim, tween(260), label = "hqNextText")
            Button(
                onClick = { app.actions.hq.advance(stepNote) },
                enabled = timeOk && stepDone,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("hq-next"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor, contentColor = btnText, disabledContainerColor = btnColor, disabledContentColor = btnText),
            ) {
                Text(
                    when {
                        !stepDone -> "先把这一步做完"
                        !timeOk -> "再待 ${mmss(readyIn)}"
                        else -> "下一环节 · ${steps[s.step + 1].seal}"
                    },
                )
            }
        }
    }
}

// ---------- 静 · 查账 ----------

@Composable
private fun SettleStep(onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val court by app.store.court.collectAsStateWithLifecycle()
    val open = Court.openOrder(court)
    var result by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf("") }
    LaunchedEffect(open?.id, result) {
        onDone(open == null)
        onNote(result ?: if (court.orders.isEmpty()) "还没签过执行令" else "上一条执行令已查账")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            BreathingRings(Night.glow, Modifier.fillMaxSize())
            Text("静", color = Night.ink, fontSize = 52.sp, fontWeight = FontWeight.Light)
        }
        if (open == null) {
            Glass { Text(result ?: "没有待查的执行令。跟着圈呼吸一分钟。", color = Night.ink, style = MaterialTheme.typography.bodyMedium) }
        } else {
            Paper(Modifier.testTag("order-check")) {
                Text("查账 · 上一条《强制执行令》", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                Text(open.text, style = MaterialTheme.typography.titleMedium)
                Text("时限 ${whenText(open.dueAt, app.store.zone)}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                if (!missing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickChip("做到了", sig.free) {
                            app.store.updateCourt { Court.check(it, open.id, true, "", System.currentTimeMillis()) }
                            result = "执行令「${open.text.take(20)}」：做到了"
                        }
                        QuickChip("没做到", sig.strict) { missing = true }
                    }
                } else {
                    OutlinedTextField(why, { why = it }, label = { Text("回想：为什么没做到，哪个瞬间放弃的") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("order-miss"))
                    BigButton("记下", {
                        app.store.updateCourt { Court.check(it, open.id, false, why, System.currentTimeMillis()) }
                        result = "执行令「${open.text.take(20)}」：没做到——${why.trim()}"
                    }, enabled = why.trim().length >= 6, color = sig.strict)
                }
            }
        }
    }
}

// ---------- 收 · 分流 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriageStep(s: HqSession, onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val work by app.store.work.collectAsStateWithLifecycle()
    val court by app.store.court.collectAsStateWithLifecycle()
    val handled = remember { mutableStateListOf<String>() }
    val loose = setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA)
    val sortedKeys = remember(court) { Court.sortedKeys(court) }
    val pool = work.notes().filter { it.threadKey.isBlank() && it.noteType in loose && it.key !in sortedKeys && it.key !in handled }.sortedBy { it.createdAt }
    val threads = remember(work) { work.threads().filter { it.threadStatus.open }.sortedByDescending { it.updatedAt }.take(5) }
    var asAction by remember { mutableStateOf(false) }
    var dump by remember { mutableStateOf("") }
    // 【精简】：脑子里最占地方的一件事，压成一句话，分进一个槽位
    var line by remember { mutableStateOf("") }
    var lineSlot by remember { mutableStateOf<Slot?>(null) }
    var lineSaved by remember { mutableStateOf<String?>(null) }
    val counts = remember(court, s.startedAt) { court.sorted.filter { it.at >= s.startedAt }.groupingBy { it.slot }.eachCount() }
    LaunchedEffect(pool.size, handled.size, lineSaved, counts) {
        // 收集箱每一条都要分流，再精简出一句话，才算过
        onDone(pool.isEmpty() && lineSaved != null)
        onNote("处理 ${handled.size} 条（① ${counts[Slot.CRISIS] ?: 0} · ② ${counts[Slot.SLIP] ?: 0} · ③ ${counts[Slot.ESCAPE] ?: 0}）；精简：${lineSaved ?: "—"}")
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { dump = if (dump.isBlank()) it else "$dump\n$it" }
    }
    fun done(n: Rec) {
        handled += n.key
        app.actions.hq.triaged()
    }
    fun sortTo(n: Rec, slot: Slot) {
        if (slot == Slot.CRISIS) app.actions.work.update(n.key, mapOf(F.TYPE to NoteType.QUESTION.label))
        app.store.updateCourt { Court.sort(it, n.key, slot, System.currentTimeMillis(), n.title) }
        done(n)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (pool.isEmpty()) "收集箱清空了。" else "还剩 ${pool.size} 条 · 已处理 ${handled.size}", color = Night.glow, style = MaterialTheme.typography.titleSmall)
        val n = pool.firstOrNull()
        if (n != null) {
            AnimatedContent(targetState = n.key, transitionSpec = { (fadeIn() + slideInVertically { it / 4 }) togetherWith fadeOut() }, label = "card") { _ ->
                Paper(Modifier.testTag("triage-card")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(n.noteType.label, sig.info)
                        Text(clock(n.createdAt), style = MaterialTheme.typography.labelSmall, color = sig.muted)
                    }
                    Text(n.title, style = MaterialTheme.typography.titleMedium)
                    if (n[F.RAW].isNotBlank() && n[F.RAW] != n.title) Text(n[F.RAW], style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("分进哪个槽位：", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.testTag("slot-crisis")) { QuickChip("${Slot.CRISIS.label} → 谋", sig.think) { sortTo(n, Slot.CRISIS) } }
                        Box(Modifier.testTag("slot-slip")) { QuickChip("${Slot.SLIP.label} → 行", sig.act) { sortTo(n, Slot.SLIP) } }
                        Box(Modifier.testTag("slot-escape")) { QuickChip("${Slot.ESCAPE.label} → 封存", sig.muted) { sortTo(n, Slot.ESCAPE) } }
                    }
                    Text(if (asAction) "或者直接变成哪件事下的一步：" else "或者直接挂到哪件事：", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickChip(if (asAction) "变成行动 ✓" else "变成行动", sig.plan) { asAction = !asAction }
                        threads.forEach { t ->
                            QuickChip(t.title.take(12), sig.info) {
                                app.actions.work.update(n.key, mapOf(F.THREAD to t.key))
                                if (asAction) app.actions.work.addAction(t.key, n.title.take(40), "", "")
                                done(n)
                            }
                        }
                    }
                }
            }
        }
        Paper(Modifier.testTag("triage-line-card")) {
            Text("【精简】此刻脑子里最占地方的一件事，压成一句话", style = MaterialTheme.typography.titleSmall)
            if (lineSaved != null) {
                Text("「$lineSaved」已分流。", color = sig.free, style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(
                    line, { line = it.replace("\n", "") },
                    label = { Text("一句话，30 字以内") },
                    isError = line.isNotBlank() && !Court.concise(line),
                    supportingText = { Text("${line.trim().length}/${Court.CONCISE_MAX}") },
                    modifier = Modifier.fillMaxWidth().testTag("triage-line"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Slot.entries.forEach { sl ->
                        Box(Modifier.testTag("line-slot-${sl.name}")) { QuickChip(sl.label + if (lineSlot == sl) " ✓" else "", sig.info) { lineSlot = sl } }
                    }
                }
                BigButton("分流这一句", {
                    val sl = lineSlot ?: return@BigButton
                    val text = line.trim()
                    val key = app.actions.work.capture(text, if (sl == Slot.CRISIS) NoteType.QUESTION else NoteType.IDEA, text, "", "", false)
                    app.store.updateCourt { Court.sort(it, key, sl, System.currentTimeMillis(), text) }
                    lineSaved = text
                }, enabled = Court.concise(line) && lineSlot != null, modifier = Modifier.testTag("line-save"))
            }
        }
        Glass {
            Text("还有别的？倒出来（收下后也要一条条分流）", color = Night.ink, style = MaterialTheme.typography.titleSmall)
            BasicTextField(
                dump, { dump = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Night.ink),
                cursorBrush = SolidColor(Night.glow),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("triage-dump"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NightChip("说") {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    if (i.resolveActivity(context.packageManager) != null) voice.launch(i)
                }
                NightChip("收下", modifier = Modifier.testTag("triage-keep")) {
                    dump.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
                        val d = Capture.guess(l)
                        app.actions.work.capture(l, d.type, d.title, d.url, "", false)
                    }
                    dump = ""
                }
            }
        }
    }
}

// ---------- 谋 · 想透一件 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThinkStep(s: HqSession, onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val court by app.store.court.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val today = DayClock.dateKey(System.currentTimeMillis(), app.store.zone)
    var topic by remember(s.stepStartedAt) { mutableStateOf<String?>(null) }
    // 第一关：底线表填好；第二关：今天的临时起意过一遍预判；第三关：写下一条判断
    var editBase by remember { mutableStateOf(false) }
    var noImpulse by remember(s.id) { mutableStateOf(false) }
    var impulse by remember { mutableStateOf("") }
    var offline by remember(s.stepStartedAt) { mutableStateOf(false) }
    val judged = court.impulses.lastOrNull { it.at >= s.startedAt }
    val impulseDone = noImpulse || judged != null
    val method = remember { Defaults.methodOf(LocalDate.parse(DayClock.dateKey(System.currentTimeMillis(), app.store.zone))) }
    val candidates = remember(work) {
        work.threads().filter { it.threadStatus in setOf(ThreadStatus.THINKING, ThreadStatus.STUCK, ThreadStatus.COLLECTING) }.sortedByDescending { it.updatedAt }.take(4) +
            work.notes().filter { it.noteType == NoteType.QUESTION }.sortedByDescending { it.createdAt }.take(3)
    }
    // 做完的标准：这一环节里写下了一条判断
    val concluded = work.notes().any { it.noteType == NoteType.CONCLUSION && it.createdAt >= s.stepStartedAt }
    LaunchedEffect(concluded, topic, court.baseline.ready, impulseDone) {
        // 不能跳：底线表、临时起意审查、一条判断，三样都要有
        onDone(court.baseline.ready && impulseDone && concluded)
        onNote(buildString {
            append(judged?.let { "临时起意「${it.text.take(20)}」→ ${it.verdict.label}" } ?: if (noImpulse) "今天没有临时起意" else "临时起意没审")
            topic?.let { k -> append("；想透「${work.rec(k)?.title ?: k}」" + if (concluded) "，写下了判断" else "") }
        })
    }
    if (!court.baseline.ready || editBase) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先把《全局战略底线表》写死：前线死令 + 退路底牌。之后每个临时起意都拿它比。", color = Night.glow, style = MaterialTheme.typography.titleSmall)
            Paper(Modifier.testTag("hq-baseline")) {
                BaselineForm(court.baseline) { base ->
                    app.store.updateCourt { Court.setBaseline(it, base, System.currentTimeMillis()) }
                    editBase = false
                }
            }
        }
        return
    }
    if (!impulseDone) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Paper {
                BaselineSummary(court, today)
                TextButton(onClick = { editBase = true }) { Text("改底线表") }
            }
            Paper(Modifier.testTag("hq-impulse")) {
                Text("【预判】今天冒出来的临时起意", style = MaterialTheme.typography.titleSmall)
                Text("想改代码、换工具、研究新东西……写下来，再判：它能让行测多拿分，还是能帮你找到下个月的工作？", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                OutlinedTextField(impulse, { impulse = it }, label = { Text("今天最想去折腾的一件事") }, modifier = Modifier.fillMaxWidth().testTag("impulse-text"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Verdict.entries.forEach { v ->
                        Box(Modifier.testTag("verdict-${v.name}")) {
                            QuickChip(v.label, if (v == Verdict.KILL) sig.strict else sig.think) {
                                if (impulse.isNotBlank()) app.store.updateCourt { Court.judge(it, "i" + System.currentTimeMillis().toString(36), impulse, v, System.currentTimeMillis()) }
                            }
                        }
                    }
                }
                TextButton(onClick = { noImpulse = true }, modifier = Modifier.testTag("impulse-none")) { Text("今天没有临时起意") }
            }
        }
        return
    }
    fun pick(k: String) {
        topic = k
        if (config.ai.ready) {
            val th = app.store.work.value.rec(k)
            app.chat.send(
                "总控时间 · 谋：我想把「${th?.title}」想透。" +
                    (th?.get(F.JUDGE)?.takeIf { it.isNotBlank() }?.let { "现在的判断：$it。" } ?: "") +
                    "用方法卡〈${method.name}〉陪我想：一次问一个问题，先要我的初判。",
            )
        }
    }
    val t = topic
    if (t == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            judged?.let { j ->
                Text(
                    if (j.verdict == Verdict.KILL) "「${j.text.take(20)}」已枪毙：不予立案，回到前线死令。" else "「${j.text.take(20)}」：${j.verdict.label}。想清楚它怎么落到底线上。",
                    color = if (j.verdict == Verdict.KILL) Color(0xFFFF8A73) else Night.dim, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("挑一件来想，写下一条判断才算过。今天的方法：${method.name}", color = Night.glow, style = MaterialTheme.typography.titleSmall)
            if (candidates.isEmpty()) Text("没有在等判断的事。挑一件手上的事：", color = Night.dim)
            (candidates.ifEmpty { work.threads().filter { it.threadStatus.open }.take(4) }).forEachIndexed { i, r ->
                Paper(Modifier.clickable {
                    pick(if (r.kind == Kind.NOTE) app.actions.work.createThread(r.title.take(30), why = r[F.RAW]).also { nk -> app.actions.work.update(r.key, mapOf(F.THREAD to nk)) } else r.key)
                }.testTag("think-topic-$i")) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (r.kind == Kind.NOTE) "问题" else r.threadStatus.label + (r[F.UNSURE].takeIf { it.isNotBlank() }?.let { " · 不确定：$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
                }
            }
            // 没东西可想也不空手：随便憋一件
            NightChip("随便憋一件：最近最卡我的是什么", modifier = Modifier.testTag("think-free")) {
                pick(app.actions.work.createThread("最近最卡我的一件事", why = "总控时间里憋出来的"))
            }
        }
        return
    }
    if (config.ai.ready && !offline) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (concluded) "判断已经记下了。还想再追就接着聊。" else "和总控来回追问，最后让它把判断记下来（或者你说“记下来”）。判断没记下，这一环不算过。", color = Night.dim, style = MaterialTheme.typography.bodySmall)
            ChatPanel(since = s.stepStartedAt, modifier = Modifier.weight(1f))
            if (!concluded) TextButton(onClick = { offline = true }) { Text("AI 卡住了：自己写判断", color = Night.dim) }
        }
    } else {
        OfflineThink(t, method.name, method.how)
    }
}

/** 没接 AI：四个问题自己憋。 */
@Composable
private fun OfflineThink(threadKey: String, methodName: String, how: String) {
    val app = LocalContext.current.zk
    val th = app.store.work.value.rec(threadKey)
    var first by remember { mutableStateOf("") }
    var against by remember { mutableStateOf("") }
    var fixed by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Paper {
            Text(th?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Text("方法卡 · $methodName：$how", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
            OutlinedTextField(first, { first = it }, label = { Text("1. 初判：我现在觉得……") }, modifier = Modifier.fillMaxWidth().testTag("think-first"))
            OutlinedTextField(against, { against = it }, label = { Text("2. 最强的反驳：如果我错了，最可能错在……") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fixed, { fixed = it }, label = { Text("3. 修正后的判断") }, modifier = Modifier.fillMaxWidth().testTag("think-fixed"))
            OutlinedTextField(next, { next = it }, label = { Text("4. 24 小时内的下一步") }, modifier = Modifier.fillMaxWidth().testTag("think-next"))
            if (!saved) {
                BigButton("记下判断", {
                    val text = "初判：$first\n反驳：$against\n修正：$fixed\n下一步：$next"
                    app.actions.work.addConclusion(text, threadKey)
                    app.actions.work.update(threadKey, buildMap {
                        if (fixed.isNotBlank()) put(F.JUDGE, fixed.trim())
                        if (next.isNotBlank()) put(F.NEXT, next.trim())
                        if (against.isNotBlank()) put(F.UNSURE, against.trim())
                    })
                    saved = true
                }, enabled = first.isNotBlank() && fixed.isNotBlank(), modifier = Modifier.testTag("think-save"))
            } else {
                Text("记下了。", color = LocalSignals.current.free)
            }
        }
    }
}

/** 嵌在总控时间里的参谋：只显示这一环节以来的对话。 */
@Composable
fun ChatPanel(since: Long, modifier: Modifier = Modifier) {
    val app = LocalContext.current.zk
    val log by app.chat.log.collectAsStateWithLifecycle()
    val busy by app.chat.busy.collectAsStateWithLifecycle()
    val items = remember(log.messages, since) { group(log.messages.filter { it.at >= since }) }
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(items.size, busy) { if (items.isNotEmpty()) list.animateScrollToItem(items.size - 1 + if (busy) 1 else 0) }
    // 总控时间里参谋要跳页面：先不跳
    LaunchedEffect(Unit) { app.chat.route.value = null }
    PaperTheme {
        Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color(0xFFFBF8F2), contentColor = Color(0xFF1F1B16)) {
            Column {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items) { it ->
                        when (it) {
                            is Item.User -> UserBubble(it.m.content, it.m.image)
                            is Item.Said -> Said(it.m.content, if (it == items.last() && !busy) it.options else emptyList()) { o -> app.chat.send(o) }
                            is Item.Steps -> Steps(it.steps)
                            is Item.Failed -> Failed(it.m.content) { log.messages.lastOrNull { m -> m.role == ChatMsg.USER && !m.hidden }?.let { u -> app.chat.send(u.content) } }
                        }
                    }
                    if (busy) item { BranchThinking(LocalSignals.current.ai, Modifier.size(40.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (input.isEmpty()) Text("回答它…", color = LocalSignals.current.muted)
                        BasicTextField(input, { input = it }, textStyle = MaterialTheme.typography.bodyLarge, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("hq-chat-input"))
                    }
                    Box(
                        Modifier.size(40.dp).background(if (input.isNotBlank() && !busy) LocalSignals.current.ai else LocalSignals.current.line, CircleShape)
                            .clickable(enabled = input.isNotBlank() && !busy) { app.chat.send(input); input = "" }
                            .testTag("hq-chat-send"),
                        contentAlignment = Alignment.Center,
                    ) { Text("↑", color = Color.White, fontWeight = FontWeight.Black) }
                }
            }
        }
    }
}

// ---------- 筹 · 明天 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TomorrowStep(onDone: (Boolean) -> Unit, onNote: (String) -> Unit, nav: NavHostController) {
    val app = LocalContext.current.zk
    val zone = app.store.zone
    val now = remember { System.currentTimeMillis() }
    val work by app.store.work.collectAsStateWithLifecycle()
    val book by app.store.plans.collectAsStateWithLifecycle()
    val plan = remember(book) { Plans.active(book, now, zone) ?: Plans.nextActive(book, now, zone) }
    val todayMain = remember(book, work) { Plans.day(book, DayClock.dateKey(now, zone))?.mainActionKey?.let { work.rec(it) } }
    val tomorrow = DayClock.date(now, zone).plusDays(1).toString()
    val picked = remember(book) { Plans.day(book, tomorrow)?.mainActionKey.orEmpty() }
    var choice by remember { mutableStateOf(picked) }
    var time by remember { mutableStateOf("明天 09:00-09:45") }
    val court by app.store.court.collectAsStateWithLifecycle()
    // 熔断：行测五步连续两天挂零，明天的主线只能是行测
    val fuse = remember(court) { app.store.fuse() }
    val isDrill = { r: Rec -> "行测" in r.title }
    var saved by remember { mutableStateOf(picked.isNotBlank() && (fuse == null || work.rec(picked)?.let(isDrill) == true)) }
    val options = remember(work, plan, fuse) {
        val fromPlan = plan?.outcomes?.filter { !it.done }?.mapNotNull { o -> work.rec(o.actionKey)?.takeIf { it.actionStatus.open } }.orEmpty()
        (fromPlan + work.actions().filter { it.actionStatus.open }.sortedByDescending { it.updatedAt }).distinctBy { it.key }
            .filter { fuse == null || isDrill(it) }.take(6)
    }
    // 没有待做的行动也能往下走（记一句）；熔断时不行：必须定一步行测
    LaunchedEffect(saved, options.isEmpty(), fuse) {
        onDone(saved || (options.isEmpty() && fuse == null))
        if (options.isEmpty() && fuse == null) onNote("没有待做的行动可以定成明天的主线")
    }
    fun drillAction() {
        val th = work.threads().firstOrNull { it.threadStatus.open && "行测" in it.title }?.key
            ?: app.actions.work.createThread("行测冲刺", why = "前线死令：" + court.baseline.frontline.ifBlank { "行测是最高优先级" })
        val key = app.actions.work.addAction(th, "行测五步训练 ${Court.DAILY_DRILLS} 道（精简·预判·联想·提问·回想）", "", "做够 ${Court.DAILY_DRILLS} 道，每道五个槽位都写了").first
        if (key != null) { choice = key; saved = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fuse?.let { f ->
            Glass(Modifier.testTag("hq-fuse")) {
                Text("熔断", color = Color(0xFFFF8A73), style = MaterialTheme.typography.titleSmall)
                Text(f.text + "。明天的主线只能是行测。", color = Night.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (court.baseline.frontline.isNotBlank()) {
            Text("前线死令：${court.baseline.frontline}" + (Court.daysLeft(court, DayClock.dateKey(now, zone))?.takeIf { it >= 0 }?.let { "（还剩 $it 天）" } ?: ""), color = Night.dim, style = MaterialTheme.typography.bodySmall)
        }
        Glass {
            Text("今天的主线", color = Night.dim, style = MaterialTheme.typography.labelLarge)
            Text(todayMain?.let { "${it.title} —— ${it.actionStatus.label}" + (it[F.RESULT].takeIf { r -> r.isNotBlank() }?.let { r -> "：$r" } ?: "") } ?: "今天没定主线", color = Night.ink, style = MaterialTheme.typography.bodyLarge)
            Text(plan?.let { "本周计划：" + it.goals.joinToString("、") { g -> g.title } + " · 交出 ${it.doneCount}/${it.outcomes.size}" } ?: "这周还没有计划", color = Night.dim, style = MaterialTheme.typography.bodySmall)
            if (plan == null) NightChip("去定本周计划") { nav.navigate("plan") }
        }
        Text("明天只推一样：", color = Night.ink, style = MaterialTheme.typography.titleSmall)
        options.forEachIndexed { i, a ->
            Paper(Modifier.clickable { choice = a.key; saved = false }.testTag("tomorrow-$i")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, style = MaterialTheme.typography.titleSmall, color = if (choice == a.key) LocalSignals.current.plan else MaterialTheme.colorScheme.onSurface)
                        Text(work.rec(a.threadKey)?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
                    }
                    if (choice == a.key) Text("✓", color = LocalSignals.current.plan, fontWeight = FontWeight.Black)
                }
            }
        }
        if (options.none(isDrill)) NightChip("建一步：行测五步 ${Court.DAILY_DRILLS} 道", modifier = Modifier.testTag("tomorrow-drill")) { drillAction() }
        if (options.isEmpty() && fuse == null) Text("没有待做的行动。回到“收”把东西挂到事项上，或者去定本周计划。", color = Night.dim)
        if (choice.isNotBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("明天 07:30-08:15", "明天 09:00-09:45", "明天 14:00-14:45", "明天 20:00-20:45").forEach { t -> NightChip(t.removePrefix("明天 ").substringBefore('-'), time == t) { time = t; saved = false } }
            }
            NightChip(if (saved) "定好了 ✓" else "就它了", saved, Modifier.testTag("tomorrow-save")) {
                app.actions.work.reschedule(choice, time)
                app.actions.plan.setMainFor(tomorrow, choice)
                saved = true
                onNote("明天主线：${work.rec(choice)?.title}（${time.removePrefix("明天 ")}）")
            }
        }
    }
}

// ---------- 行 · 对账 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LedgerStep(onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.zk
    val day by app.store.today.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val work by app.store.work.collectAsStateWithLifecycle()
    val zone = app.store.zone
    val now = remember { System.currentTimeMillis() }
    val ys by produceState<YishouStatus?>(null) { value = (YishouClient.status(context) as? YishouClient.Result.Ok)?.value }
    val todayStart = DayClock.startOf(DayClock.date(now, zone), zone)
    val reminded = work.events.filter { it.at >= todayStart && (it.type == "remind" || it.type == "followup") }.map { it.actionKey }.toSet()
    val started = work.events.filter { it.at >= todayStart && it.type == "start" }.map { it.actionKey }.toSet()
    val ignored = (reminded - started).mapNotNull { work.rec(it)?.title }
    var chosen by remember { mutableStateOf<String?>(null) }
    val court by app.store.court.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val today = DayClock.dateKey(now, zone)
    val habitsOk = Court.habitsDone(court, today)
    val slips = Court.pendingSlips(court)
    // 今天还有没有别的踩坑：要么说“没有”，要么写一条规则
    var noSlip by remember { mutableStateOf(false) }
    val rulesAtStart = remember { court.rules.size }
    val wroteRule = court.rules.size > rulesAtStart
    val drills = Court.drillsOn(court, today)
    val fuse = remember(court) { app.store.fuse() }
    LaunchedEffect(chosen, habitsOk, slips.size, noSlip, wroteRule) {
        // 三层都过了才放行：微习惯点名、待写的规则写完、今天的踩坑交代清楚
        onDone(habitsOk && slips.isEmpty() && (noSlip || wroteRule))
        onNote(buildString {
            append("微习惯 ").append(if (habitsOk) "全确认" else "没确认完")
            append("；新规则 ${court.rules.size - rulesAtStart} 条；行测五步 $drills/${Court.DAILY_DRILLS}")
            append("；").append(chosen?.let { "明天的约束：$it" } ?: "明天照今天的来")
        })
    }
    val report = remember(work, day) { com.zongkong.core.plan.DayReport.text(app.actions.dayReport()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paper(Modifier.testTag("hq-habits")) {
            Text("① 微习惯点名", style = MaterialTheme.typography.titleSmall)
            HabitRollCall(court, today) { id -> app.store.updateCourt { Court.confirmHabit(it, today, id) } }
        }
        Paper(Modifier.testTag("hq-rules")) {
            Text("② 防错规避库", style = MaterialTheme.typography.titleSmall)
            val p = slips.firstOrNull()
            if (p != null) {
                RuleForm("「${p.line.ifBlank { "收集箱里的一条漏洞" }}」写成死规则（还有 ${slips.size} 条）", tag = "hq-slip") { scene, action, slip ->
                    app.store.updateCourt { Court.addRule(it, "r" + System.currentTimeMillis().toString(36), scene, action, slip, p.noteKey, System.currentTimeMillis()) ?: it }
                }
            } else if (wroteRule) {
                Text("写进去了。", color = sig.free, style = MaterialTheme.typography.bodyMedium)
            } else if (noSlip) {
                Text("今天没有踩坑。", color = sig.free, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("今天还踩了什么坑？吃一堑，写成“遇到 A 就做 B”。", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                RuleForm("今天的坑", tag = "hq-rule") { scene, action, slip ->
                    app.store.updateCourt { Court.addRule(it, "r" + System.currentTimeMillis().toString(36), scene, action, slip, "", System.currentTimeMillis()) ?: it }
                }
                TextButton(onClick = { noSlip = true }, modifier = Modifier.testTag("slip-none")) { Text("今天没有踩坑") }
            }
        }
        Paper {
            Text("③ 五步思维 × 行测：今天 $drills/${Court.DAILY_DRILLS} 道", style = MaterialTheme.typography.titleSmall)
            Text(
                fuse?.text ?: if (drills >= Court.DAILY_DRILLS) "今天够数了。" else "白天在 四部 → 行为管理部 → 行测五步 里做；连续两天挂零就熔断。",
                style = MaterialTheme.typography.bodySmall, color = if (fuse != null) sig.strict else sig.muted,
            )
        }
        Glass {
            Text("今天的清单", color = Night.dim, style = MaterialTheme.typography.labelLarge)
            Text(report.substringAfter('\n').trim(), color = Night.ink, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glass(Modifier.weight(1f)) {
                Text("一手", color = Night.dim, style = MaterialTheme.typography.labelMedium)
                Text(ys?.let { "${it.doneSets}/${it.targetSets} 组" } ?: "—", color = Night.ink, style = MaterialTheme.typography.titleLarge.merge(Mono))
            }
            Glass(Modifier.weight(1f)) {
                Text("娱乐", color = Night.dim, style = MaterialTheme.typography.labelMedium)
                Text("${day.playMinutes} 分", color = Night.ink, style = MaterialTheme.typography.titleLarge.merge(Mono))
            }
            Glass(Modifier.weight(1f)) {
                Text("被弹回", color = Night.dim, style = MaterialTheme.typography.labelMedium)
                Text("${day.bounces} 次", color = Night.ink, style = MaterialTheme.typography.titleLarge.merge(Mono))
            }
        }
        Glass {
            Text("今天被你忽略的提醒", color = Night.dim, style = MaterialTheme.typography.labelLarge)
            if (ignored.isEmpty()) Text("没有。提醒了就开始了。", color = Night.ink)
            ignored.forEach { Text("· $it", color = Night.ink, style = MaterialTheme.typography.bodyMedium) }
        }
        Text("想加一条明天的约束就点（不加也能往下走）：", color = Night.ink, style = MaterialTheme.typography.titleSmall)
        val rules = Policy.rulesOf(config)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (config.dailyQuotaMin > 30) NightChip("娱乐上限少 30 分钟", chosen == "娱乐上限少 30 分钟") {
                app.store.updateConfig { c -> Policy.setRules(c, rules.copy(dailyQuotaMin = c.dailyQuotaMin - 30), System.currentTimeMillis()).config }
                chosen = "娱乐上限少 30 分钟"
            }
            NightChip("一手最多放行 3 分钟", chosen == "一手最多放行 3 分钟") {
                app.store.updateConfig { c -> Policy.setYishou(c, true, 3, System.currentTimeMillis()).config }
                chosen = "一手最多放行 3 分钟"
            }
            NightChip("总控时间多 10 分钟", chosen == "总控时间多 10 分钟") {
                app.store.updateConfig { c -> Policy.desiredHq(c).let { d -> Policy.setHq(c, d.copy(minutes = (maxOf(c.hq.minutes, d.minutes) + 10).coerceAtMost(90)), System.currentTimeMillis()).config } }
                chosen = "总控时间多 10 分钟"
            }
            NightChip("不加，照今天的来", chosen == "不加", Modifier.testTag("ledger-none")) { chosen = "不加" }
        }
        Text("加的约束立即生效；想放宽要等 24 小时。", color = Night.dim, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- 封 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SealStep(
    s: HqSession, now: Long, onNote: (String) -> Unit,
    order: String, onOrder: (String) -> Unit, orderDue: Long, onDue: (Long) -> Unit,
) {
    val zone = LocalContext.current.zk.store.zone
    /** 下一个 hh:mm（今天过了就是明天）。 */
    fun next(h: Int, m: Int): Long {
        val z = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val t = z.toLocalDate().atTime(h, m).atZone(zone).let { if (!it.isAfter(z)) it.plusDays(1) else it }
        return t.toInstant().toEpochMilli()
    }
    var pickedDue by remember(s.id) { mutableStateOf(-1) }
    val dues = listOf("1 小时内" to now + 3_600_000L, "9:00 前" to next(9, 0), "12:00 前" to next(12, 0), "20:00 前" to next(20, 0))
    val auto = remember(s.notes) {
        HqStep.entries.filter { it != HqStep.SEAL && it != HqStep.SETTLE }.mapNotNull { st -> s.notes[st.name]?.takeIf { it.isNotBlank() }?.let { "${st.seal}：$it" } }.joinToString("\n")
    }
    var text by remember(s.id) { mutableStateOf(auto) }
    LaunchedEffect(text) { onNote(text) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Paper(Modifier.testTag("order-card")) {
            Text("《强制执行令》", style = MaterialTheme.typography.titleSmall)
            Text("只写一个动作，具体到打开什么、做多少。比如：打开行测言语理解做 5 道题，每道跑完五步。下次总控时间一开场先查它。", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
            OutlinedTextField(order, onOrder, label = { Text("唯一的动作") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("order-text"))
            Text("时限", style = MaterialTheme.typography.labelLarge, color = LocalSignals.current.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dues.forEachIndexed { i, (label, at) ->
                    Box(Modifier.testTag("order-due-$i")) { QuickChip(label + if (pickedDue == i && orderDue > 0) " ✓" else "", LocalSignals.current.hq) { pickedDue = i; onDue(at) } }
                }
            }
            if (orderDue > 0) Text("到 ${whenText(orderDue, zone)}", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
        }
        Paper {
            Text("这一次", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("seal-text"))
            Text("封存后记成一条“验收”记录（会同步到 Notion），并交给今天的日终验收。", style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
        }
        if (now < s.endsAt) {
            Text("时间还没到。点上面亮着的印章，回去再修一轮：再清一些、再想一件、再看看明天。", color = Night.dim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- 设置 ----------

/** 总控时间的设置。收紧立即生效；关掉、缩短、推迟、放开应用这些放宽，24 小时后生效。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HqSettingsScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val config by app.store.config.collectAsStateWithLifecycle()
    // 从“排队中的设置”开始改：放宽的那部分还在等 24 小时，不能一保存就把它冲掉
    val desired = remember(config.hq, config.pending) { Policy.desiredHq(config) }
    var h by remember(desired) { mutableStateOf(desired) }
    var timeText by remember(desired) { mutableStateOf(DayClock.hhmm(desired.at)) }
    val waiting = config.pending.firstOrNull { it.op is com.zongkong.core.ChangeOp.Hq }
    var addApp by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackBar("总控时间 · 设置", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("收紧立即生效；关掉、缩短、推迟、放开更多应用这些放宽，24 小时后生效。一次保存里两样都有，收紧的照样马上生效。")
            waiting?.let { w ->
                Panel(accent = sig.warn, modifier = Modifier.testTag("hq-waiting")) {
                    Text("排队中：${w.summary}", style = MaterialTheme.typography.titleSmall)
                    Hint("${clock(w.effectiveAt)} 生效（“我 → 待生效的放宽”里可以撤回）。现在用的还是：每天 ${DayClock.hhmm(config.hq.at)}，${config.hq.minutes} 分钟。")
                }
            }
            Panel {
                SwitchRow("每天的总控时间", h.enabled) { h = h.copy(enabled = it) }
                OutlinedTextField(timeText, { timeText = it; DayClock.parse(it)?.let { m -> h = h.copy(at = m) } }, label = { Text("几点开始（HH:mm）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("多长", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 20, 30, 45, 60).forEach { m -> QuickChip("$m 分钟" + if (h.minutes == m) " ✓" else "", sig.plan) { h = h.copy(minutes = m) } } }
                SwitchRow("到点 5 分钟没进来就自动进入", h.autoLock) { h = h.copy(autoLock = it) }
            }
            Panel {
                Text("总控时间里还能用的应用", style = MaterialTheme.typography.titleSmall)
                Hint("总控自己、电话、紧急呼叫、闹钟、系统界面总是能用。")
                h.allowed.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(com.zongkong.app.data.AppNames.label(app, p), modifier = Modifier.weight(1f))
                        if (p !in config.hq.allowed) Tag("24 小时后可用", sig.warn)
                        TextButton(onClick = { h = h.copy(allowed = h.allowed - p) }) { Text("去掉", color = sig.muted) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(addApp, { addApp = it }, label = { Text("加一个（应用名或包名）") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        com.zongkong.app.data.AppNames.resolve(app, addApp)?.let { h = h.copy(allowed = h.allowed + it) }
                        addApp = ""
                    }) { Text("加") }
                }
            }
            Panel {
                SwitchRow("震动催你（静音也震）", h.vibrate) { h = h.copy(vibrate = it) }
                SwitchRow("失联阶梯（1 天主动找你、2 天严管、3 天强制进入）", h.escalation) { h = h.copy(escalation = it) }
                SwitchRow("总控定时主动找你（早上、午后）", h.proactive) { h = h.copy(proactive = it) }
                SwitchRow("总控时间里把总控的话读出来", h.speak) { h = h.copy(speak = it) }
                Hint("睡觉时段 ${DayClock.hhmm(h.quietStart)}–${DayClock.hhmm(h.quietEnd)} 只轻震，不连续震。")
            }
            BigButton("保存", {
                var msg = ""
                app.store.updateConfig { c -> Policy.setHq(c, h, System.currentTimeMillis()).also { msg = it.message }.config }
                message = msg
            })
        }
    }
    OutcomeDialog(message) { message = null }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
