package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.work.F
import com.zongkong.core.work.StuckReason
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.delay

// ---------- 开始前 ----------

/** 开始一步：看一眼做什么、做到什么算完，决定要不要开专注锁。 */
@Composable
fun StartRoute(key: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val sig = LocalSignals.current
    var lock by remember { mutableStateOf(config.focusMinutes > 0) }
    var criteria by remember(a?.key) { mutableStateOf(a?.get(F.CRITERIA).orEmpty()) }

    // 已经在做这一步：直接进计时页
    LaunchedEffect(work.session?.actionKey) {
        if (work.session?.actionKey == key) nav.navigate("session") { popUpTo("start/$key") { inclusive = true } }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("开始", { nav.popBackStack() })
        if (a == null) {
            Hint("找不到这一步。", Modifier.padding(16.dp)); return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            work.rec(a.threadKey)?.let { Text(it.title, style = MaterialTheme.typography.labelLarge, color = sig.muted) }
            Text(a.title, style = MaterialTheme.typography.headlineSmall.merge(SerifTitle))
            if (a[F.BREAK].isNotBlank()) Panel(accent = sig.think) {
                Text("上次停在", style = MaterialTheme.typography.labelLarge, color = sig.think)
                Text(a[F.BREAK], style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                criteria, { criteria = it }, label = { Text("做到什么算完") },
                placeholder = { Text("例：写完第二段 + 发给朋友") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("专注锁")
                    Hint(if (lock) "做这一步时打开拦截名单里的应用会被弹回（到计划结束，或 ${config.focusMinutes} 分钟）。记断点或做完就解开。" else "不开锁")
                }
                Switch(checked = lock, onCheckedChange = { lock = it })
            }
            // 计划外：不拦，只问一句
            val weekPlan = remember { app.actions.plan.active() }
            val inPlan = weekPlan == null || com.zongkong.core.plan.Plans.inPlan(weekPlan, a)
            var added by remember { mutableStateOf(false) }
            if (!inPlan && !added) {
                val main = remember { com.zongkong.core.plan.Plans.mainAction(app.store.plans.value, work, System.currentTimeMillis(), app.store.zone) }
                Panel(accent = sig.warn, modifier = Modifier.testTag("offplan")) {
                    Text("这件不在本周计划里", style = MaterialTheme.typography.titleSmall, color = sig.warn)
                    Hint("不拦你。想一下：是临时冒出来的，还是本来就该在计划里？")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.actions.plan.addActionToPlan(key, "开始时发现该加进计划"); added = true }) { Text("加进本周计划") }
                        if (main != null && main.key != key) {
                            TextButton(onClick = { nav.navigate("start/${main.key}") { popUpTo("start/$key") { inclusive = true } } }) { Text("回到主线") }
                        }
                    }
                }
            }
            // 同时让一手开一局陪练
            val yishouInstalled = remember { com.zongkong.app.link.YishouClient.installed(app) }
            var withYishou by remember { mutableStateOf(false) }
            if (yishouInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("同时在一手开一局陪练")
                        Hint(if (withYishou) "这一步交给一手，一手开陪练窗口，按这一步一手一手地问你。" else "不开")
                    }
                    Switch(checked = withYishou, onCheckedChange = { withYishou = it })
                }
            }
            work.session?.takeIf { it.actionKey != key }?.let { s ->
                Hint("正在做的「${work.rec(s.actionKey)?.title}」会停止计时（状态不变，可以之后继续）。")
            }
            Button(onClick = {
                if (criteria != a[F.CRITERIA]) app.actions.work.update(key, mapOf(F.CRITERIA to criteria.trim()))
                app.actions.work.start(key, lock)
                if (!inPlan && !added) app.store.updateWork { w -> com.zongkong.core.work.WorkOps.event(w, com.zongkong.core.work.Event(System.currentTimeMillis(), "offplan", key)) }
                if (withYishou) {
                    app.appScope.launch {
                        com.zongkong.core.Link.taskOrder(app.store.work.value, key)?.let { com.zongkong.app.link.YishouClient.setTask(app, it) }
                        com.zongkong.app.link.YishouClient.startWindow(app)
                        com.zongkong.app.link.YishouClient.openWindow(app)
                    }
                }
                com.zongkong.app.system.Notifications.cancelTask(app, key)
                nav.navigate("session") { popUpTo("start/$key") { inclusive = true } }
            }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start-confirm")) { Text("开始") }
        }
    }
}

// ---------- 正在做 ----------

@Composable
fun SessionScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val s = work.session
    val a = s?.let { work.rec(it.actionKey) }
    val sig = LocalSignals.current
    val now = rememberNow(1_000)
    var progress by rememberSaveable(s?.actionKey) { mutableStateOf(s?.progress.orEmpty()) }

    LaunchedEffect(progress) {
        delay(500)
        if (s != null && progress != s.progress) app.actions.work.progress(progress)
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("正在做", { nav.popBackStack() })
        if (s == null || a == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("现在没有正在做的一步。")
                Button(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }) { Text("回首页") }
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 离开过一阵：先问一句
            if (s.leftAt > 0 && now - s.leftAt > 3 * 60_000) {
                Panel(accent = sig.warn) {
                    Text("离开了 ${span(now - s.leftAt)}", style = MaterialTheme.typography.titleSmall)
                    Hint("还在做这件事吗？不做了的话记个断点，下次从这里接着。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.actions.work.back() }) { Text("继续做") }
                        OutlinedButton(onClick = { nav.navigate("pause/${a.key}") }) { Text("记个断点") }
                    }
                }
            }
            work.rec(a.threadKey)?.let { Text(it.title, style = MaterialTheme.typography.labelLarge, color = sig.muted) }
            Text(a.title, style = MaterialTheme.typography.headlineSmall.merge(SerifTitle))
            // 大号计时：外面一圈呼吸圈（Endel 式），数字翻动（Fliqlo 式）
            val el = (now - s.startedAt).coerceAtLeast(0) / 1000
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                BreathingRings(if (s.lockUntil > now) sig.warn else MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FlipNumber("%02d:%02d".format(el / 60, el % 60))
                    Text(if (s.lockUntil > now) "专注锁 · 到 ${clock(s.lockUntil)}" else "计时中", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                }
            }
            if (a[F.CRITERIA].isNotBlank()) {
                Text("做到：${a[F.CRITERIA]}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (s.lockUntil > now) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("专注锁到 ${clock(s.lockUntil)}，娱乐应用会被弹回", Modifier.weight(1f))
                    TextButton(onClick = { app.actions.work.extendLock(15) }) { Text("+15 分") }
                    TextButton(onClick = { app.actions.work.unlock() }) { Text("解锁", color = sig.muted) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("专注锁没开", Modifier.weight(1f))
                    TextButton(onClick = { app.actions.work.extendLock(25) }) { Text("开 25 分钟") }
                }
            }
            OutlinedTextField(
                progress, { progress = it }, label = { Text("进展（随手记，离开时会带进断点）") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { nav.navigate("complete/${a.key}") }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("session-done")) { Text("做完了") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate("pause/${a.key}") }, modifier = Modifier.weight(1f)) { Text("先停一下") }
                OutlinedButton(onClick = { nav.navigate("stuck/${a.key}") }, modifier = Modifier.weight(1f)) { Text("卡住了") }
            }
            YishouHandoff(a.key)
        }
    }
}

// ---------- 中途离开：记断点 ----------

@Composable
fun PauseScreen(key: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val t = a?.let { work.rec(it.threadKey) }
    var done by remember { mutableStateOf(work.session?.takeIf { it.actionKey == key }?.progress.orEmpty()) }
    var blocker by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BackBar("记个断点", { nav.popBackStack() })
        if (a == null) { Hint("找不到这一步。", Modifier.padding(16.dp)); return@Column }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(a.title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
            Hint("三句话，下次回来不用从头想。写进行动、事项和 Notion。")
            OutlinedTextField(done, { done = it }, label = { Text("做到哪了") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(blocker, { blocker = it }, label = { Text("卡在哪（没有就空着）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(next, { next = it }, label = { Text("回来第一件事做什么") }, placeholder = { Text(t?.get(F.NEXT).orEmpty()) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                app.actions.work.pause(key, done, blocker, next)
                nav.navigate("home") { popUpTo("home") { inclusive = true } }
            }, modifier = Modifier.fillMaxWidth()) { Text("记下，先停") }
        }
    }
}

// ---------- 做完：结果回到记录里 ----------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompleteScreen(key: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val sig = LocalSignals.current
    var result by remember { mutableStateOf(work.session?.takeIf { it.actionKey == key }?.progress.orEmpty()) }
    var met by remember { mutableStateOf(WorkOps.Met.YES) }
    var next by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar("记录结果", { nav.popBackStack() })
        if (a == null) { Hint("找不到这一步。", Modifier.padding(16.dp)); return@Column }
        val t = work.rec(a.threadKey)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(a.title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
            if (!saved) {
                Panel {
                    Text("完成依据", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                    Text(a[F.CRITERIA].ifBlank { "（没写）" }, style = MaterialTheme.typography.bodyLarge)
                }
                Text("对照完成依据：", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkOps.Met.entries.forEach { m -> FilterChip(selected = met == m, onClick = { met = m }, label = { Text(m.label) }) }
                }
                Hint(
                    when (met) {
                        WorkOps.Met.YES -> "→ 完成"
                        WorkOps.Met.PARTIAL -> "→ 待确认：结果先记下，可以带去 GPT 判断够不够"
                        WorkOps.Met.NO -> "→ 卡住：接着问你卡在哪"
                    },
                )
                OutlinedTextField(result, { result = it }, label = { Text("结果（数字、产出、链接都行）") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("result-field"))
                OutlinedTextField(next, { next = it }, label = { Text("下一步（不知道就空着，交给 GPT 判断）") }, modifier = Modifier.fillMaxWidth().testTag("next-field"))
                Button(onClick = {
                    app.actions.work.complete(key, result, met, next)
                    com.zongkong.app.system.Notifications.cancelTask(app, key)
                    if (met == WorkOps.Met.NO) nav.navigate("stuck/$key") { popUpTo("complete/$key") { inclusive = true } } else saved = true
                }, enabled = result.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("complete-save")) { Text("记下结果") }
            } else {
                Panel(accent = sig.free) {
                    Text("结果已记下", style = MaterialTheme.typography.titleMedium, color = sig.free)
                    Hint("写进了行动、事项断点和一条“结果”记录，会同步到 Notion。GPT 下次读这件事就能看到。")
                }
                if (t != null) Button(onClick = {
                    app.prompt = app.actions.work.gptPrompt(t.key, "我刚完成了「${a.title}」，结果：${result.trim()}。请根据结果判断下一步。")
                    app.promptTitle = t.title
                    app.store.awaitingGpt = t.key
                    nav.navigate("gpt")
                }, modifier = Modifier.fillMaxWidth()) { Text("带着结果去 GPT 判断下一步") }
                OutlinedButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("回首页") }
            }
        }
    }
}

// ---------- 没推进：先定位原因，再给对应的帮助 ----------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StuckScreen(key: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val sig = LocalSignals.current
    var reason by remember { mutableStateOf<StuckReason?>(null) }
    var detail by remember { mutableStateOf("") }
    var resched by remember { mutableStateOf(false) }
    var editCriteria by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar("没推进？", { nav.popBackStack() })
        if (a == null) { Hint("找不到这一步。", Modifier.padding(16.dp)); return@Column }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(a.title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
            Text("先看看是哪一种：", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StuckReason.entries.forEach { r -> FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(r.label) }) }
            }
            val r = reason
            if (r == null) {
                Hint("忘了、不会做、不清楚、时间冲突、去娱乐了、不想做——原因不同，办法不同。")
                return@Column
            }
            Hint(r.hint)
            val goHome = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }
            val start = { lock: Boolean, minutes: Int? ->
                app.actions.work.noteReason(key, r)
                app.actions.work.start(key, lock, minutes)
                nav.navigate("session") { popUpTo("home") }
            }
            val toGpt = {
                app.actions.work.stuck(key, r, detail)
                app.prompt = app.actions.work.stuckPrompt(key, r, detail)
                app.promptTitle = a.title
                app.store.awaitingGpt = a.threadKey
                nav.navigate("gpt") { popUpTo("home") }
            }
            when (r) {
                StuckReason.FORGOT -> Panel {
                    Text("没关系，现在想起来了。", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { start(true, null) }, modifier = Modifier.fillMaxWidth()) { Text("现在开始") }
                    OutlinedButton(onClick = { app.actions.work.noteReason(key, r); app.actions.work.snooze(key, 30); goHome() }, modifier = Modifier.fillMaxWidth()) { Text("30 分钟后再提醒我") }
                    Hint("如果总是忘：把这类事排在固定时间，或者提前一点提醒。“查看结果”里能看到哪个时段的提醒最有用。")
                }
                StuckReason.CANT -> Panel {
                    Text("拆小，先做能做的一点。", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(detail, { detail = it }, label = { Text("卡在哪一步（一句话）") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = toGpt, modifier = Modifier.fillMaxWidth()) { Text("带着前情去 GPT 拆成小步") }
                    OutlinedButton(onClick = { start(true, 5) }, modifier = Modifier.fillMaxWidth()) { Text("先做 5 分钟，做到哪算哪") }
                }
                StuckReason.UNCLEAR -> Panel {
                    Text("先把这一步说清楚。", style = MaterialTheme.typography.titleSmall)
                    FieldRow("现在的完成依据", a[F.CRITERIA], "（没写——这多半就是原因）") { editCriteria = true }
                    OutlinedButton(onClick = { editCriteria = true }, modifier = Modifier.fillMaxWidth()) { Text("我自己改写完成依据") }
                    OutlinedTextField(detail, { detail = it }, label = { Text("哪里不清楚") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = toGpt, modifier = Modifier.fillMaxWidth()) { Text("去 GPT 澄清") }
                }
                StuckReason.CONFLICT -> Panel {
                    Text("换个时间，别硬挤。", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { app.actions.work.noteReason(key, r); resched = true }, modifier = Modifier.fillMaxWidth()) { Text("改期") }
                    Hint("改期会同步到 Notion，GPT 下次能看到你这件事被推迟过几次。")
                }
                StuckReason.DISTRACTED -> Panel {
                    Text("现在回来就好。开着专注锁做一段。", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { start(true, 25) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = sig.strict)) {
                        Text("开专注锁做 25 分钟")
                    }
                    Hint("专注锁期间，拦截名单里的应用一打开就被弹回。")
                }
                StuckReason.RELUCTANT -> Panel {
                    Text("不用一口气做完。", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { start(true, 5) }, modifier = Modifier.fillMaxWidth()) { Text("只做 5 分钟") }
                    OutlinedButton(onClick = { app.actions.work.noteReason(key, r); resched = true }, modifier = Modifier.fillMaxWidth()) { Text("改个时间") }
                    OutlinedTextField(detail, { detail = it }, label = { Text("如果真的不该做了，写原因") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        app.actions.work.noteReason(key, r)
                        app.actions.work.cancel(key, detail.ifBlank { "不想做" })
                        goHome()
                    }, enabled = detail.isNotBlank()) { Text("取消这一步", color = sig.muted) }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { app.actions.work.stuck(key, r, detail); goHome() }) { Text("先记下“卡住”，回头再说", color = sig.muted) }
        }
    }
    if (resched) RescheduleDialog(key) { resched = false; nav.navigate("home") { popUpTo("home") { inclusive = true } } }
    if (editCriteria) EditDialog("完成依据", a?.get(F.CRITERIA).orEmpty(), hint = "做到什么程度算完成？拿什么证明？", onDismiss = { editCriteria = false }) { v ->
        app.actions.work.update(key, mapOf(F.CRITERIA to v.trim()))
    }
}


/** 计时数字：每一位单独往上翻。 */
@Composable
fun FlipNumber(text: String) {
    Row {
        text.forEachIndexed { i, ch ->
            androidx.compose.animation.AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut())
                },
                label = "flip$i",
            ) { c ->
                Text(c.toString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 64.sp)
            }
        }
    }
}

/**
 * 交给一手陪练：这一步设为一手的当前任务，一手按这一步一手一手地问你。
 * 一手没装就给下载链接；装了但太旧就说更新。
 */
@Composable
fun YishouHandoff(actionKey: String) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val installed = remember { com.zongkong.app.link.YishouClient.installed(context) }
    Panel(accent = sig.act) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeptSeal(com.zongkong.core.Dept.ACT, size = 34.dp, filled = true)
            Column(Modifier.weight(1f)) {
                Text("交给一手陪练", style = MaterialTheme.typography.titleSmall)
                Hint(if (installed) "一手会就这一步一手一手地问你，答一手算一手。" else "还没装一手。装上以后，这一步可以交给它陪你练。")
            }
        }
        if (installed) {
            OutlinedButton(onClick = {
                scope.launch {
                    val order = com.zongkong.core.Link.taskOrder(app.store.work.value, actionKey)
                    msg = if (order == null) "找不到这一步" else when (val r = com.zongkong.app.link.YishouClient.setTask(context, order)) {
                        is com.zongkong.app.link.YishouClient.Result.Ok -> {
                            com.zongkong.app.link.YishouClient.open(context)
                            r.value.ifBlank { "已交给一手" }
                        }
                        is com.zongkong.app.link.YishouClient.Result.Err -> r.message
                    }
                }
            }, modifier = Modifier.fillMaxWidth().testTag("to-yishou")) { Text("交给一手，打开一手") }
        } else {
            OutlinedButton(onClick = { launchTarget(context, com.zongkong.app.link.YishouClient.DOWNLOAD) }, modifier = Modifier.fillMaxWidth()) { Text("下载一手") }
        }
        msg?.let { Hint(it) }
    }
}
