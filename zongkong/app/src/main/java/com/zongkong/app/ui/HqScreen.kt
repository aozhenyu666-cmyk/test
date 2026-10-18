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
import androidx.compose.material3.AlertDialog
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
    val now = rememberNow(1_000)
    var finished by remember { mutableStateOf<String?>(null) }
    // 封存会移除 HqRun，验收协程必须由仍在显示的总控页持有。
    val finishScope = rememberCoroutineScope()
    // 总控时间里按返回键不离开（做完才解锁）
    BackHandler(enabled = session != null && now >= session.suspendedUntil) {}
    NightSky(Modifier.fillMaxSize().testTag("hq-screen")) {
        when {
            finished != null -> HqDone(finished!!, nav)
            session != null && now < session.suspendedUntil -> Column(
                Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("总控时间暂时缓行", color = Night.ink, style = MaterialTheme.typography.headlineSmall)
                Text("${mmss(session.suspendedUntil - now)} 后恢复。环节进度保留，未算完成，其他任务和限制继续生效。", color = Night.dim)
                Button(onClick = { app.actions.control.resumeHq() }) { Text("现在继续") }
                TextButton(onClick = { nav.navigate("control") }) { Text("查看补交清单", color = Night.glow) }
                TextButton(onClick = { nav.navigate("home") }) { Text("回到今天", color = Night.glow) }
            }
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
                "按 静 → 收 → 谋 → 筹 → 行 → 封，把四个部门修一遍。随便憋，也能憋出东西。",
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
                "每个环节有最短时间，事没做完不能往下走",
                "整段时间到了、六个环节都过了，才能封存解锁",
                "真有急事：紧急退出，写清楚原因（会记下来）",
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
    var stepDone by remember(s.step) { mutableStateOf(s.current == HqStep.SETTLE) }
    var stepNote by remember(s.step) { mutableStateOf("") }
    var exiting by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
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
                    HqStep.SETTLE -> SettleStep()
                    HqStep.INFO -> TriageStep(s, { stepDone = it }, { stepNote = it })
                    HqStep.THINK -> ThinkStep(s, { stepDone = it }, { stepNote = it })
                    HqStep.PLAN -> TomorrowStep({ stepDone = it }, { stepNote = it }, nav)
                    HqStep.ACT -> LedgerStep({ stepDone = it }, { stepNote = it })
                    HqStep.SEAL -> SealStep(s, now) { stepNote = it }
                }
            }
        }

        // ---------- 底部 ----------
        if (s.current == HqStep.SEAL) {
            val can = Hq.canFinish(s, now)
            val btnColor by animateColorAsState(if (can && !finishing) Night.glow else Night.card, tween(260), label = "hqBtn")
            val btnText by animateColorAsState(if (can && !finishing) Night.top else Night.dim, tween(260), label = "hqBtnText")
            Button(
                onClick = {
                    if (finishing) return@Button
                    finishing = true
                    onFinish(stepNote)
                },
                enabled = can && !finishing,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("hq-seal"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = btnColor, contentColor = btnText, disabledContainerColor = btnColor, disabledContentColor = btnText),
            ) { Text(if (finishing) "封存中…" else if (can) "封存，解锁" else if (s.endsAt > now) "还有 ${mmss(s.endsAt - now)}：点上面的印章回去再修一轮" else "读一眼总结，${mmss(Hq.finishAt(s) - now)} 后就能封存") }
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
        DeferButton(com.zongkong.core.control.DeferralKind.HQ, s.id)
        if (s.trigger != HqTrigger.UNANSWERED) TextButton(onClick = { exiting = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("紧急退出", color = Night.dim) }
    }

    if (exiting) {
        var reason by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { exiting = false },
            title = { Text("真的要现在退出？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会记下来：哪天、第几个环节、为什么。电话不用退出，本来就能打。")
                    OutlinedTextField(reason, { reason = it }, label = { Text("为什么（至少 6 个字）") }, minLines = 2)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    err = app.actions.hq.exitEarly(reason)
                    if (err == null) {
                        exiting = false
                        nav.navigate("home") { popUpTo("home") { inclusive = true } }
                    }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { exiting = false }) { Text("留下来") } },
        )
    }
}

// ---------- 静 ----------

@Composable
private fun SettleStep() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BreathingRings(Night.glow, Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("静", color = Night.ink, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text("跟着圈呼吸。一分钟。", color = Night.dim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- 收 · 清库 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriageStep(s: HqSession, onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val skipped = remember { mutableStateListOf<String>() }
    val handled = remember { mutableStateListOf<String>() }
    val loose = setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA)
    val pool = work.notes().filter { it.threadKey.isBlank() && it.noteType in loose && it.key !in skipped && it.key !in handled }.sortedBy { it.createdAt }
    val threads = remember(work) { work.threads().filter { it.threadStatus.open }.sortedByDescending { it.updatedAt }.take(5) }
    var asAction by remember { mutableStateOf(false) }
    var dump by remember { mutableStateOf("") }
    LaunchedEffect(pool.size, handled.size) {
        onDone(pool.isEmpty())
        onNote("处理 ${handled.size} 条，先放着 ${skipped.size} 条")
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { dump = if (dump.isBlank()) it else "$dump\n$it" }
    }
    fun done(n: Rec) {
        handled += n.key
        app.actions.hq.triaged()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (pool.isEmpty()) "收集箱清空了。" else "还剩 ${pool.size} 条 · 已处理 ${handled.size}", color = Night.glow, style = MaterialTheme.typography.titleSmall)
        val n = pool.firstOrNull()
        if (n != null) {
            AnimatedContent(targetState = n.key, transitionSpec = { (fadeIn() + slideInVertically { it / 4 }) togetherWith fadeOut() }, label = "card") { _ ->
                Paper(Modifier.testTag("triage-card")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(n.noteType.label, LocalSignals.current.info)
                        Text(clock(n.createdAt), style = MaterialTheme.typography.labelSmall, color = LocalSignals.current.muted)
                    }
                    Text(n.title, style = MaterialTheme.typography.titleMedium)
                    if (n[F.RAW].isNotBlank() && n[F.RAW] != n.title) Text(n[F.RAW], style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickChip(if (asAction) "变成行动 ✓" else "变成行动", LocalSignals.current.plan) { asAction = !asAction }
                        QuickChip("转成问题 → 谋", LocalSignals.current.think) {
                            app.actions.work.update(n.key, mapOf(F.TYPE to NoteType.QUESTION.label))
                            done(n)
                        }
                    }
                    Text(if (asAction) "变成哪件事下的一步：" else "挂到哪件事：", style = MaterialTheme.typography.labelLarge, color = LocalSignals.current.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        threads.forEachIndexed { i, t ->
                            QuickChip(t.title.take(12), LocalSignals.current.info, ) {
                                app.actions.work.update(n.key, mapOf(F.THREAD to t.key))
                                if (asAction) app.actions.work.addAction(t.key, n.title.take(40), "", "")
                                done(n)
                            }
                        }
                        QuickChip("新的一件事", LocalSignals.current.info) {
                            val k = app.actions.work.createThread(n.title.take(30))
                            app.actions.work.update(n.key, mapOf(F.THREAD to k))
                            if (asAction) app.actions.work.addAction(k, n.title.take(40), "", "")
                            done(n)
                        }
                    }
                    TextButton(onClick = { skipped += n.key }, modifier = Modifier.testTag("triage-skip")) { Text("先放着") }
                }
            }
        }
        Glass {
            Text("脑子里还有什么？倒出来", color = Night.ink, style = MaterialTheme.typography.titleSmall)
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
                    dump.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                        val d = Capture.guess(line)
                        app.actions.work.capture(line, d.type, d.title, d.url, "", false)
                    }
                    dump = ""
                }
            }
        }
    }
}

// ---------- 谋 · 想透一件 ----------

@Composable
private fun ThinkStep(s: HqSession, onDone: (Boolean) -> Unit, onNote: (String) -> Unit) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    var topic by remember(s.stepStartedAt) { mutableStateOf<String?>(null) }
    val method = remember { Defaults.methodOf(LocalDate.parse(DayClock.dateKey(System.currentTimeMillis(), app.store.zone))) }
    val candidates = remember(work) {
        work.threads().filter { it.threadStatus in setOf(ThreadStatus.THINKING, ThreadStatus.STUCK, ThreadStatus.COLLECTING) }.sortedByDescending { it.updatedAt }.take(4) +
            work.notes().filter { it.noteType == NoteType.QUESTION }.sortedByDescending { it.createdAt }.take(3)
    }
    // 做完的标准：这一环节里写下了一条判断
    val concluded = work.notes().any { it.noteType == NoteType.CONCLUSION && it.createdAt >= s.stepStartedAt }
    LaunchedEffect(concluded, topic) {
        onDone(concluded)
        topic?.let { k -> onNote("想透「${work.rec(k)?.title ?: k}」" + if (concluded) "，写下了判断" else "") }
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
            Text("挑一件来想。今天的方法：${method.name}", color = Night.glow, style = MaterialTheme.typography.titleSmall)
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
    if (config.ai.ready) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (concluded) "判断已经记下了。还想再追就接着聊。" else "和总控来回追问，最后让它把判断记下来（或者你说“记下来”）。", color = Night.dim, style = MaterialTheme.typography.bodySmall)
            ChatPanel(since = s.stepStartedAt, modifier = Modifier.weight(1f))
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
    var saved by remember { mutableStateOf(picked.isNotBlank()) }
    val options = remember(work, plan) {
        val fromPlan = plan?.outcomes?.filter { !it.done }?.mapNotNull { o -> work.rec(o.actionKey)?.takeIf { it.actionStatus.open } }.orEmpty()
        (fromPlan + work.actions().filter { it.actionStatus.open }.sortedByDescending { it.updatedAt }).distinctBy { it.key }.take(6)
    }
    // 没有待做的行动也能往下走（记一句），不能卡死在这里
    LaunchedEffect(saved, options.isEmpty()) {
        onDone(saved || options.isEmpty())
        if (options.isEmpty()) onNote("没有待做的行动可以定成明天的主线")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        if (options.isEmpty()) Text("没有待做的行动。回到“收”把东西挂到事项上，或者去定本周计划。", color = Night.dim)
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
    LaunchedEffect(chosen) { onDone(chosen != null); chosen?.let { onNote("明天的约束：$it") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text("明天加一条约束：", color = Night.ink, style = MaterialTheme.typography.titleSmall)
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

@Composable
private fun SealStep(s: HqSession, now: Long, onNote: (String) -> Unit) {
    val auto = remember(s.notes) {
        HqStep.entries.filter { it != HqStep.SEAL && it != HqStep.SETTLE }.mapNotNull { st -> s.notes[st.name]?.takeIf { it.isNotBlank() }?.let { "${st.seal}：$it" } }.joinToString("\n")
    }
    var text by remember(s.id) { mutableStateOf(auto) }
    LaunchedEffect(text) { onNote(text) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
