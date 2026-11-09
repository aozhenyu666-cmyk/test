package com.zongkong.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.court.Baseline
import com.zongkong.core.court.Court
import com.zongkong.core.court.CourtBook
import com.zongkong.core.court.Drill
import com.zongkong.core.court.DrillStep
import java.time.Instant
import java.time.ZoneId

/** 3月5日 21:30 */
fun whenText(ms: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(ms).atZone(zone).let { "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

private fun newId(prefix: String) = prefix + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

/** 公审档案的“今天”和“开始用公审那天”（熔断要用）。 */
private fun courtDays(b: CourtBook, now: Long, zone: ZoneId): Pair<String, String?> =
    DayClock.dateKey(now, zone) to (if (b.since > 0) DayClock.dateKey(b.since, zone) else null)

// ---------- 可复用的小块（总控时间里也用） ----------

/** 《全局战略底线表》的编辑表单。 */
@Composable
fun BaselineForm(initial: Baseline, onSave: (Baseline) -> Unit) {
    var exam by remember(initial) { mutableStateOf(initial.exam) }
    var date by remember(initial) { mutableStateOf(initial.examDate) }
    var front by remember(initial) { mutableStateOf(initial.frontline) }
    var f1 by remember(initial) { mutableStateOf(initial.fallback.getOrElse(0) { "" }) }
    var f2 by remember(initial) { mutableStateOf(initial.fallback.getOrElse(1) { "" }) }
    var start by remember(initial) { mutableStateOf(initial.fallbackStart.ifBlank { "考完第 2 天" }) }
    val dateOk = date.isBlank() || runCatching { java.time.LocalDate.parse(date.trim()) }.isSuccess
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(exam, { exam = it }, label = { Text("在备考什么（比如：省考）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(date, { date = it }, label = { Text("笔试日期（2026-11-30 这样写）") }, singleLine = true, isError = !dateOk, modifier = Modifier.fillMaxWidth().testTag("baseline-date"))
        OutlinedTextField(front, { front = it }, label = { Text("前线死令：剩下这些天一定要做到的") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("baseline-front"))
        OutlinedTextField(f1, { f1 = it }, label = { Text("退路底牌 1：没进面立刻去投的岗位") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("baseline-f1"))
        OutlinedTextField(f2, { f2 = it }, label = { Text("退路底牌 2（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(start, { start = it }, label = { Text("退路什么时候启动") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        BigButton(
            "写进底线表",
            { onSave(Baseline(exam.trim(), date.trim(), front.trim(), listOf(f1, f2), start.trim())) },
            enabled = dateOk && front.isNotBlank() && f1.isNotBlank(),
            modifier = Modifier.testTag("baseline-save"),
        )
    }
}

/** 底线表的一段摘要。 */
@Composable
fun BaselineSummary(b: CourtBook, today: String) {
    val base = b.baseline
    val left = Court.daysLeft(b, today)
    Text(
        (base.exam.ifBlank { "备考" }) + (left?.let { if (it >= 0) " · 还剩 $it 天" else " · 已经考完" } ?: " · 没填笔试日期"),
        style = MaterialTheme.typography.titleSmall,
    )
    Text("前线死令：${base.frontline.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodyMedium)
    Text("退路底牌：${base.fallback.joinToString("；").ifBlank { "（空）" }}" + base.fallbackStart.takeIf { it.isNotBlank() }?.let { "（$it 启动）" }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
}

/** 微习惯点名：一条条用眼睛看一眼，再点确认。 */
@Composable
fun HabitRollCall(b: CourtBook, today: String, onConfirm: (String) -> Unit) {
    val sig = LocalSignals.current
    val done = b.habitChecks[today].orEmpty()
    Text("现在停下，用眼睛看一眼。连续 ${Court.habitStreak(b, today)}/${Court.HABIT_DAYS} 天", style = MaterialTheme.typography.bodySmall, color = sig.muted)
    b.habits.forEachIndexed { i, h ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (h.id in done) Text("看过了 ✓", color = sig.free, style = MaterialTheme.typography.labelLarge)
            else TextButton(onClick = { onConfirm(h.id) }, modifier = Modifier.testTag("habit-$i")) { Text("看了，确认") }
        }
    }
}

/** 防错规避库的一条：回想 → 场景 A → 动作 B。 */
@Composable
fun RuleForm(title: String, sceneHint: String = "", tag: String = "rule", onSave: (scene: String, action: String, slip: String) -> Unit) {
    var slip by remember(title) { mutableStateOf("") }
    var scene by remember(title) { mutableStateOf(sceneHint) }
    var action by remember(title) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(slip, { slip = it }, label = { Text("回想：刚才哪个瞬间走神了") }, modifier = Modifier.fillMaxWidth().testTag("$tag-slip"))
        OutlinedTextField(scene, { scene = it }, label = { Text("预判：如果遇到（什么信号 / 场景 A）") }, modifier = Modifier.fillMaxWidth().testTag("$tag-scene"))
        OutlinedTextField(action, { action = it }, label = { Text("立刻执行（动作 B）") }, modifier = Modifier.fillMaxWidth().testTag("$tag-action"))
        BigButton("写进防错规避库", { onSave(scene, action, slip) }, enabled = scene.isNotBlank() && action.isNotBlank(), modifier = Modifier.testTag("$tag-save"))
    }
}

// ---------- 公审档案 ----------

/** 公审档案：底线表、执行令、熔断、微习惯、防错规避库、临时起意记录、导出到 Obsidian。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourtScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val sig = LocalSignals.current
    val zone = store.zone
    val now = rememberNow(30_000)
    val b by store.court.collectAsStateWithLifecycle()
    val (today, since) = courtDays(b, now, zone)
    val fuse = remember(b, today) { Court.fuse(b, today, since) }
    var editBase by remember { mutableStateOf(false) }
    var editHabits by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val md = { Court.markdown(store.court.value, today, since) { whenText(it, zone) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(md().toByteArray()) }
                "存好了。放进 Obsidian 仓库的文件夹里，打开就能看到这几张表。"
            }.getOrElse { "没存上：${it.message}" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("公审档案", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hint("四个部门是一条流水线：收 → 谋 → 筹 → 行，上一环没过，下一环不放行。每天的总控时间就是一次公审，这里是它的底牌。")
            fuse?.let { f ->
                Panel(accent = sig.strict, pulse = true, onClick = { nav.navigate("drill") }) {
                    Text("熔断中", style = MaterialTheme.typography.titleMedium, color = sig.strict)
                    Text(f.text + "。娱乐应用先关着。", style = MaterialTheme.typography.bodyMedium)
                    BigButton("去做行测五步", { nav.navigate("drill") }, color = sig.strict)
                }
            }

            SectionLabel("谋划思考部 · 全局战略底线表")
            Panel(accent = sig.think, modifier = Modifier.testTag("court-baseline")) {
                if (!b.baseline.ready || editBase) {
                    BaselineForm(b.baseline) { base ->
                        store.updateCourt { Court.setBaseline(it, base, System.currentTimeMillis()) }
                        editBase = false
                    }
                } else {
                    BaselineSummary(b, today)
                    Hint("以后每个临时起意都拿它比：能让行测多拿分，还是能帮你找到下个月的工作？答不上来就枪毙。")
                    TextButton(onClick = { editBase = true }) { Text("改底线表") }
                }
            }

            SectionLabel("封 · 强制执行令")
            Panel(accent = sig.hq) {
                val open = Court.openOrder(b)
                if (open == null) Hint("没有待查的执行令。每次总控时间封存前签一条，下次一开场查账。")
                else {
                    Text("待查：${open.text}", style = MaterialTheme.typography.titleSmall)
                    Hint("时限 ${whenText(open.dueAt, zone)}。下次总控时间一开场先查这条。")
                }
                b.orders.filterNot { it.open }.takeLast(5).reversed().forEach { o ->
                    Text((if (o.done) "✓ " else "✗ ") + o.text + o.note.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = if (o.done) sig.free else sig.strict)
                }
            }

            SectionLabel("行为管理部 · ③ 五步思维 × 行测")
            Panel(accent = sig.act, onClick = { nav.navigate("drill") }) {
                Text("今天 ${Court.drillsOn(b, today)}/${Court.DAILY_DRILLS} 道", style = MaterialTheme.typography.titleMedium)
                Hint("每道题跑完五个槽位才算：精简、预判、联想、提问、回想。连续两天挂零就熔断。")
                BigButton("做一道", { nav.navigate("drill") }, color = sig.act)
            }

            SectionLabel("行为管理部 · ① 微习惯盲区表")
            Panel(accent = sig.act) {
                if (editHabits) {
                    var labels by remember(b.habits) { mutableStateOf(b.habits.map { it.label } + List(Court.MAX_HABITS - b.habits.size) { "" }) }
                    labels.forEachIndexed { i, l ->
                        OutlinedTextField(l, { v -> labels = labels.toMutableList().also { it[i] = v } }, label = { Text("第 ${i + 1} 条（高频漏做的事）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    BigButton("保存", { store.updateCourt { Court.setHabits(it, labels) }; editHabits = false })
                } else {
                    HabitRollCall(b, today) { id -> store.updateCourt { Court.confirmHabit(it, today, id) } }
                    Hint("每天早上和总控时间前，总控会点一次名。只记最高频漏做的 3 件。")
                    TextButton(onClick = { editHabits = true }) { Text("改这 3 条") }
                }
            }

            SectionLabel("行为管理部 · ② 防错规避库")
            Panel(accent = sig.act) {
                Court.pendingSlips(b).firstOrNull()?.let { p ->
                    RuleForm("待写规则：${p.line.ifBlank { "收集箱里的一条漏洞" }}", tag = "court-rule-pending") { scene, action, slip ->
                        store.updateCourt { Court.addRule(it, newId("r"), scene, action, slip, p.noteKey, System.currentTimeMillis()) ?: it }
                    }
                } ?: RuleForm("踩了一个坑？写成死规则", tag = "court-rule") { scene, action, slip ->
                    store.updateCourt { Court.addRule(it, newId("r"), scene, action, slip, "", System.currentTimeMillis()) ?: it }
                    message = "写进去了。"
                }
                if (b.rules.isNotEmpty()) SectionLabel("已有 ${b.rules.size} 条")
                b.rules.takeLast(20).reversed().forEach { r ->
                    Text("如果 ${r.scene} → 立刻 ${r.action}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (b.impulses.isNotEmpty()) {
                SectionLabel("谋划思考部 · 临时起意审查")
                Panel {
                    b.impulses.takeLast(10).reversed().forEach { i ->
                        Text("${whenText(i.at, zone)}  ${i.text} → ${i.verdict.label}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionLabel("导出到 Obsidian")
            Panel {
                Hint("整本档案写成一篇 Markdown（全是表格）。存进 Obsidian 仓库的文件夹，或者直接分享给 Obsidian。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { save.launch("公审档案 $today.md") }) { Text("存成文件") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "公审档案 $today")
                            .putExtra(Intent.EXTRA_TEXT, md())
                        context.startActivity(Intent.createChooser(send, "分享公审档案"))
                    }) { Text("分享") }
                }
            }
        }
    }
    OutcomeDialog(message) { message = null }
}

// ---------- 行测五步 ----------

/** 行测五步：每道题跑完 精简 → 预判 → 联想 → 提问 → 回想 才算一题。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val store = app.store
    val sig = LocalSignals.current
    val zone = store.zone
    val now = rememberNow(30_000)
    val b by store.court.collectAsStateWithLifecycle()
    val (today, since) = courtDays(b, now, zone)
    val count = Court.drillsOn(b, today)
    val fuse = remember(b, today) { Court.fuse(b, today, since) }
    var round by remember { mutableStateOf(0) }
    var topic by remember(round) { mutableStateOf("") }
    val answers = remember(round) { androidx.compose.runtime.mutableStateMapOf<DrillStep, String>() }
    var result by remember(round) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BackBar("行测五步", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(accent = if (fuse != null) sig.strict else sig.act) {
                Text("今天 $count/${Court.DAILY_DRILLS} 道", style = MaterialTheme.typography.headlineSmall)
                Hint(fuse?.let { it.text + "。" } ?: "不看视频，只做真题。每道题对着这五个槽位过一遍：既学了行测，又练了思维。")
                Court.daysLeft(b, today)?.takeIf { it >= 0 }?.let { Hint("离笔试还剩 $it 天。") }
            }
            Panel {
                OutlinedTextField(topic, { topic = it }, label = { Text("哪一题（模块 + 题号，或一句题干）") }, modifier = Modifier.fillMaxWidth().testTag("drill-topic"))
                DrillStep.entries.forEachIndexed { i, st ->
                    val v = answers[st].orEmpty()
                    val bad = st == DrillStep.CONDENSE && v.isNotBlank() && !Court.concise(v)
                    OutlinedTextField(
                        v, { answers[st] = it },
                        label = { Text("${i + 1}. 【${st.label}】${st.ask}") },
                        isError = bad,
                        supportingText = if (st == DrillStep.CONDENSE) ({ Text("${v.trim().length}/${Court.CONCISE_MAX}") }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("drill-${st.name.lowercase()}"),
                    )
                }
                Text("结果", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("对", "错", "蒙对的").forEach { r -> QuickChip(if (result == r) "$r ✓" else r, sig.act) { result = r } }
                }
                val d = Drill(newId("d"), System.currentTimeMillis(), today, topic, answers.toMap(), result)
                BigButton(
                    "交这一题",
                    {
                        store.updateCourt { Court.addDrill(it, d.copy(at = System.currentTimeMillis())) ?: it }
                        store.touch()
                        round++
                    },
                    enabled = d.complete && result.isNotBlank() && Court.concise(answers[DrillStep.CONDENSE].orEmpty()),
                    color = sig.act,
                    modifier = Modifier.testTag("drill-save"),
                )
                Hint("五个槽位都写了、精简在 30 字以内，才能交。")
            }
            val recent = b.drills.filter { it.date == today }.takeLast(5).reversed()
            if (recent.isNotEmpty()) {
                SectionLabel("今天做过的")
                recent.forEach { r ->
                    Panel {
                        Text("${r.topic}（${r.result}）", style = MaterialTheme.typography.titleSmall)
                        Hint("精简：${r.steps[DrillStep.CONDENSE].orEmpty()}；回想：${r.steps[DrillStep.RECALL].orEmpty()}")
                    }
                }
            }
        }
    }
}
