package com.behaviordept.app.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.ExamKind
import com.behaviordept.app.data.MatchResult
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.SubSkillStatus
import com.behaviordept.app.data.Template
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.components.SevenDays
import com.behaviordept.app.ui.components.ShareBars
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlin.math.roundToInt

/**
 * 一个技能的全貌：当前重点（锁 7 天）→ 专项练 → 实战复测；诊断图；各子能力的标准。
 */
@Composable
fun SkillScreen(
    skillId: Long,
    onBack: () -> Unit,
    onLogMatch: (Long) -> Unit,
    onLogExam: (Long) -> Unit,
    onStartDrill: (Long) -> Unit,
) {
    val vm = appViewModel { SkillViewModel(it, skillId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val d = state ?: return
    val p = Paper.colors
    var editing by remember { mutableStateOf<SubSkill?>(null) }
    var addingDrillTo by remember { mutableStateOf<SubSkill?>(null) }
    var askGoal by remember { mutableStateOf(false) }
    var retestFor by remember { mutableStateOf<SubSkill?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuietButton("‹ 返回", onBack)
        Column {
            SectionLabel(Template.label(d.skill.template))
            Text(d.skill.name, style = MaterialTheme.typography.headlineLarge, color = p.ink)
        }

        when (d.skill.template) {
            Template.COMPETITIVE -> InkButton("登记一局", onClick = { onLogMatch(d.skill.id) })
            Template.EXAM -> InkButton("登记一次考试", onClick = { onLogExam(d.skill.id) })
        }

        vm.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = p.red)
        }

        FocusCard(
            d = d,
            vm = vm,
            onStartDrill = onStartDrill,
            onAddDrill = { addingDrillTo = it },
            onMotorRetest = { retestFor = it },
        )

        if (d.skill.template == Template.COMPETITIVE || d.skill.template == Template.EXAM) {
            DiagnosisCard(d, vm)
        }

        if (d.trend.size >= 2) {
            PaperCard {
                SectionLabel(d.trendLabel)
                Spacer(Modifier.height(10.dp))
                TrendChart(d.trend)
            }
        }

        // 各子能力的标准
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标准", style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.weight(1f))
            QuietButton(if (vm.busy && vm.standardProposals.isEmpty()) "AI 拆解中…" else "让 AI 重写", onClick = { askGoal = true })
        }
        Hint("先有标准，再有练习。每条标准都要能看出来做没做到。")
        d.subs.forEach { sub ->
            StandardCard(
                sub = sub,
                isFocus = sub.id == d.focus?.id,
                canPick = d.skill.template == Template.MOTOR || d.diagnosisReady || d.focus == null,
                onEdit = { editing = sub },
                onPick = { vm.setFocus(sub) },
            )
        }

        RecentRecords(d, vm)

        QuietButton("删除这个技能", onClick = { confirmDelete = true })
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { sub ->
        StandardDialog(sub, onDismiss = { editing = null }) { text ->
            vm.saveStandard(sub, text)
            editing = null
        }
    }
    addingDrillTo?.let { sub ->
        DrillDialog(sub, onDismiss = { addingDrillTo = null }) { title, method, minutes, metric ->
            vm.addDrill(sub, title, method, minutes, metric)
            addingDrillTo = null
        }
    }
    if (askGoal) {
        GoalDialog(onDismiss = { askGoal = false }) { goal ->
            askGoal = false
            vm.regenerateStandards(goal)
        }
    }
    if (vm.standardProposals.isNotEmpty()) {
        StandardsPreviewDialog(vm.standardProposals, onApply = { vm.applyStandards() }, onDiscard = { vm.discardStandards() })
    }
    retestFor?.let { sub ->
        MotorRetestDialog(sub, onDismiss = { retestFor = null }) { met, total, note ->
            vm.motorRetest(sub, met, total, note)
            retestFor = null
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.page,
            title = { Text("删除「${d.skill.name}」？", color = p.ink) },
            text = { Text("标准、练习、对局记录一起删除。事件日志保留。", color = p.ink2) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSkill(onBack) }) {
                    Text("删除", color = p.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = p.ink) } },
        )
    }
}

private fun pct(v: Double?): String = v?.let { "${(it * 100).roundToInt()}%" } ?: "—"

/** 当前重点：7 天格、练习、复测。 */
@Composable
private fun FocusCard(
    d: SkillDetail,
    vm: SkillViewModel,
    onStartDrill: (Long) -> Unit,
    onAddDrill: (SubSkill) -> Unit,
    onMotorRetest: (SubSkill) -> Unit,
) {
    val p = Paper.colors
    val focus = d.focus
    val plan = d.plan
    PaperCard {
        SectionLabel("当前唯一重点")
        Spacer(Modifier.height(8.dp))
        if (focus == null || plan == null) {
            Text("还没有定", style = MaterialTheme.typography.headlineMedium, color = p.ink2)
            Spacer(Modifier.height(6.dp))
            Hint(
                when (d.skill.template) {
                    Template.COMPETITIVE -> if (d.diagnosisReady) "看下面的死因占比，把占比最高的一类设为重点。"
                    else "每局结束花 30 秒登记。阵亡满 ${Diagnosis.MIN_DEATHS} 次（现在 ${d.deaths} 次）就能诊断出短板。"
                    Template.EXAM -> if (d.diagnosisReady) "看下面各模块的失分占比，把丢分最多的模块设为重点。"
                    else "登记一次模考或真题，就能看出哪个模块丢分最多。"
                    else -> "从下面的标准里选一个最拖后腿的子能力，设为重点。一次只练一个。"
                },
            )
            return@PaperCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(focus.name, style = MaterialTheme.typography.displaySmall, color = p.red, modifier = Modifier.weight(1f))
            val since = d.skill.focusSince
            if (since != null) {
                Text(
                    "${Time.md(since)} 起 · 锁定到 ${FocusRules.lockedUntil(d.skill)?.let { Time.md(it) } ?: ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.ink2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val since = d.skill.focusSince
        if (since != null) {
            val start = Time.dateOf(since)
            val todayIdx = (Time.today().toEpochDay() - start.toEpochDay()).toInt()
            SevenDays(
                checked = (0 until 7).map { start.plusDays(it.toLong()) in plan.drillDates },
                todayIndex = todayIdx,
            )
            Spacer(Modifier.height(6.dp))
            Hint("这 7 天至少练 ${FocusRules.MIN_DRILL_DAYS} 天，之后回到实战复测。")
        }

        // 复测
        when (val r = plan.retest) {
            is RetestState.Ready -> {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().background(p.redWash, CardShape).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("实战复测结果", style = MaterialTheme.typography.labelLarge, color = p.red)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(pct(r.before), style = MaterialTheme.typography.headlineMedium, color = p.ink2)
                        Text("  →  ", style = MaterialTheme.typography.titleMedium, color = p.ink2)
                        Text(pct(r.after), style = MaterialTheme.typography.displaySmall, color = p.red)
                    }
                    Text(
                        (if (r.lowerIsBetter) "这一类死因占比" else "达标 / 正确率") +
                            if (r.improved) "：有进步。可以通过，换下一个短板；也可以再巩固一轮。" else "：还没见效。建议继续练这一项，或者换练法。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.ink,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineButton("继续练这一项", onClick = { vm.continueFocus() }, modifier = Modifier.weight(1f))
                        LineButton("通过，换短板", onClick = { vm.passFocus() }, modifier = Modifier.weight(1f))
                    }
                }
            }
            is RetestState.Collecting -> {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().background(p.redWash, CardShape).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("该实战复测了", style = MaterialTheme.typography.labelLarge, color = p.red)
                    Text(
                        when (d.skill.template) {
                            Template.COMPETITIVE -> "正常打，每局登记。复测期已登记阵亡 ${r.have}/${r.need} 次，满了自动对比「${focus.name}」占比。"
                            Template.EXAM -> "做一套模考或真题并登记，自动对比「${focus.name}」的正确率。"
                            else -> "实战录一段（可以让 Gemini 看），逐条对照标准，记下达标几条。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.ink,
                    )
                    if (d.skill.template == Template.MOTOR) LineButton("对照标准打分", onClick = { onMotorRetest(focus) })
                }
            }
            RetestState.NotDue -> Unit
        }

        // 练习
        Spacer(Modifier.height(18.dp))
        SectionLabel("专项练习")
        Spacer(Modifier.height(6.dp))
        if (plan.drills.isEmpty()) Hint("还没有练习。让 AI 按标准出几个，或者自己写一个。")
        plan.drills.forEach { drill ->
            DrillRow(drill, d, isNext = drill.id == plan.nextDrill?.id, onStart = { onStartDrill(drill.id) }, onRemove = { vm.removeDrill(drill) })
        }
        vm.proposals.forEach { prop ->
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).background(p.paper, CardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("AI 提议：${prop.title}", style = MaterialTheme.typography.titleSmall, color = p.ink)
                Text(prop.method, style = MaterialTheme.typography.bodySmall, color = p.ink2)
                Text("${prop.minutes} 分钟 · 记 ${prop.metric.ifBlank { "—" }}", style = MaterialTheme.typography.labelMedium, color = p.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineButton("确认，固定下来", onClick = { vm.acceptProposal(prop) })
                    QuietButton("不要", onClick = { vm.rejectProposal(prop) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton(if (vm.busy && vm.proposals.isEmpty()) "AI 出题中…" else "让 AI 出 3 个练习", onClick = { vm.proposeDrills() }, enabled = !vm.busy, modifier = Modifier.weight(1f))
            LineButton("自己写一个", onClick = { onAddDrill(focus) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DrillRow(drill: Drill, d: SkillDetail, isNext: Boolean, onStart: () -> Unit, onRemove: () -> Unit) {
    val p = Paper.colors
    val values = d.drillEvents.filter { it.refId == drill.id }.sortedBy { it.time }.mapNotNull { it.value }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(drill.title.ifBlank { "练习" }, style = MaterialTheme.typography.titleMedium, color = p.ink)
                Text(
                    "${drill.minutes} 分钟" + (if (drill.metric.isNotBlank()) " · 记 ${drill.metric}" else "") +
                        (values.lastOrNull()?.let { " · 上次 ${fmt(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isNext) p.red else p.ink2,
                )
            }
            LineButton("开始", onClick = onStart)
        }
        Text(drill.method, style = MaterialTheme.typography.bodySmall, color = p.ink2, modifier = Modifier.padding(top = 4.dp))
        QuietButton("停用", onClick = onRemove)
    }
}

private fun fmt(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

@Composable
private fun DiagnosisCard(d: SkillDetail, vm: SkillViewModel) {
    val p = Paper.colors
    PaperCard {
        if (d.skill.template == Template.COMPETITIVE) {
            SectionLabel("死因占比 · 最近 ${minOf(d.deaths, Diagnosis.DEATH_WINDOW)} 次阵亡")
        } else {
            SectionLabel("失分占比 · 最近 ${minOf(d.sittings.size, Diagnosis.SITTING_WINDOW)} 次考试")
        }
        Spacer(Modifier.height(12.dp))
        if (d.shares.all { it.count == 0 }) {
            Hint(if (d.skill.template == Template.COMPETITIVE) "还没有阵亡记录。" else "还没有考试记录。")
            return@PaperCard
        }
        ShareBars(d.shares, highlight = d.focus?.name, unit = if (d.skill.template == Template.EXAM) "题" else "次")
        val sug = d.suggestion
        if (d.focus == null && d.diagnosisReady && sug != null) {
            val sub = d.subs.firstOrNull { it.name == sug.name }
            if (sub != null) {
                Spacer(Modifier.height(14.dp))
                InkButton("设为当前重点：${sub.name}", onClick = { vm.setFocus(sub) })
            }
        } else if (d.skill.template == Template.COMPETITIVE && !d.diagnosisReady) {
            Spacer(Modifier.height(10.dp))
            Hint("阵亡 ${d.deaths}/${Diagnosis.MIN_DEATHS} 次，满了才给结论，样本太少容易看错。")
        }
    }
}

@Composable
private fun StandardCard(sub: SubSkill, isFocus: Boolean, canPick: Boolean, onEdit: () -> Unit, onPick: () -> Unit) {
    val p = Paper.colors
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub.name, style = MaterialTheme.typography.titleLarge, color = if (isFocus) p.red else p.ink, modifier = Modifier.weight(1f))
            Text(
                when {
                    isFocus -> "当前重点"
                    sub.status == SubSkillStatus.PASSED -> "已通过"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocus) p.red else p.ink2,
            )
        }
        Spacer(Modifier.height(8.dp))
        val lines = sub.standard.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) Hint("还没写标准。没有标准的项目不能开始训练。")
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("·", style = MaterialTheme.typography.bodyMedium, color = p.ink2, modifier = Modifier.width(14.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = p.ink)
            }
        }
        Row {
            QuietButton("改标准", onClick = onEdit)
            if (!isFocus && canPick && lines.isNotEmpty()) QuietButton("设为当前重点", onClick = onPick)
        }
    }
}

@Composable
private fun RecentRecords(d: SkillDetail, vm: SkillViewModel) {
    val p = Paper.colors
    when (d.skill.template) {
        Template.COMPETITIVE -> if (d.matches.isNotEmpty()) {
            PaperCard {
                val extracted = d.matches.count { it.result == MatchResult.EXTRACTED }
                SectionLabel("最近对局 · 共 ${d.matches.size} 局，撤离 $extracted 局")
                d.matches.take(12).forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Time.md(m.time) + " " + Time.hm(m.time), style = MaterialTheme.typography.labelMedium, color = p.ink2, modifier = Modifier.width(96.dp))
                        Text(
                            if (m.result == MatchResult.DIED) "阵亡 · ${m.causeCategory.orEmpty()}" else "撤离",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (m.result == MatchResult.DIED) p.ink else p.ink2,
                            modifier = Modifier.weight(1f),
                        )
                        QuietButton("删", onClick = { vm.deleteMatch(m) })
                    }
                    if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall, color = p.ink2)
                }
            }
        }
        Template.EXAM -> if (d.sittings.isNotEmpty()) {
            PaperCard {
                SectionLabel("考试记录")
                d.sittings.take(8).forEach { st ->
                    val acc = Diagnosis.sittingAccuracy(d.sections, st.id)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${ExamKind.label(st.kind)} · ${Time.md(st.time)}" + (st.score?.let { " · 行测 ${fmt(it)}" } ?: "") +
                                    (st.essayScore?.let { " · 申论 ${fmt(it)}" } ?: ""),
                                style = MaterialTheme.typography.titleSmall,
                                color = p.ink,
                            )
                            Text(
                                "总正确率 ${pct(acc)} · " + d.sections.filter { it.sittingId == st.id }
                                    .joinToString("  ") { "${it.module.take(2)} ${it.correct}/${it.total}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = p.ink2,
                            )
                        }
                        QuietButton("删", onClick = { vm.deleteSitting(st) })
                    }
                }
            }
        }
        else -> Unit
    }
}
