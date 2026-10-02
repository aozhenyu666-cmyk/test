package com.behaviordept.app.training

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.SessionType
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.Divider
import com.behaviordept.app.ui.components.FocusBar
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.NumberField
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.components.Sparkline
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DrillFocusViewModel(private val c: AppContainer, private val drillId: Long) : ViewModel() {
    var drill by mutableStateOf<Drill?>(null)
        private set
    var sub by mutableStateOf<SubSkill?>(null)
        private set
    var skill by mutableStateOf<Skill?>(null)
        private set
    var startedAt by mutableLongStateOf(Time.now())
        private set
    var ended by mutableStateOf(false)
        private set
    var value by mutableStateOf("")
    var note by mutableStateOf("")
    var feedback by mutableStateOf<DrillFeedback?>(null)
        private set
    var recordedWithoutNumber by mutableStateOf(false)
        private set

    private var sessionId: Long? = null
    private var heartbeat: Job? = null

    init {
        viewModelScope.launch {
            c.sessions.closeAbandoned()
            val d = c.training.drill(drillId) ?: return@launch
            drill = d
            val s = c.training.subSkill(d.subSkillId)
            sub = s
            skill = s?.let { c.training.skill(it.skillId) }
            startedAt = Time.now()
            sessionId = c.sessions.start(SessionType.DRILL, d.id, "专项练：${d.title}")
            heartbeat = viewModelScope.launch {
                while (isActive && !ended) {
                    delay(30_000)
                    sessionId?.let { c.sessions.heartbeat(it) }
                }
            }
        }
    }

    fun record() {
        val d = drill ?: return
        val v = value.toDoubleOrNull()
        viewModelScope.launch {
            c.training.logDrill(d, v, note)
            recordedWithoutNumber = v == null
            feedback = drillFeedback(d, c.training.drillEvents(d.id))
        }
    }

    fun end(onDone: () -> Unit) {
        if (ended) return
        ended = true
        heartbeat?.cancel()
        viewModelScope.launch {
            sessionId?.let { c.sessions.finish(it) }
            onDone()
        }
    }

    override fun onCleared() {
        if (!ended) sessionId?.let { id -> c.appScope.launch { c.sessions.finish(id) } }
        super.onCleared()
    }
}

/** 专项练：全屏专注，只练当前短板；练完当场和以往的数字对比（设计原则 6）。 */
@Composable
fun DrillFocusScreen(drillId: Long, onExit: () -> Unit) {
    val vm = appViewModel { DrillFocusViewModel(it, drillId) }
    val p = Paper.colors
    var confirmEnd by remember { mutableStateOf(false) }
    val endNow: () -> Unit = { vm.end(onExit) }
    val requestEnd: () -> Unit = {
        if (vm.feedback != null) {
            endNow()
        } else {
            confirmEnd = true
        }
    }
    BackHandler(enabled = !vm.ended) { requestEnd() }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        FocusBar("专项练 · ${vm.skill?.name.orEmpty()}", vm.drill?.title.orEmpty(), vm.startedAt, requestEnd)
        Divider()
        val d = vm.drill
        val sub = vm.sub
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (d == null || sub == null) {
                Text("准备中…", color = p.ink2)
                return@Column
            }
            val fb = vm.feedback
            if (fb != null) {
                FeedbackView(d, fb, vm.recordedWithoutNumber)
                InkButton("结束专注", onClick = endNow)
                return@Column
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("这次只练：${sub.name}")
                Text(d.title, style = MaterialTheme.typography.headlineMedium, color = p.ink)
                Text("建议 ${d.minutes} 分钟", style = MaterialTheme.typography.labelLarge, color = p.ink2)
            }
            PaperCard {
                SectionLabel("做法")
                Spacer(Modifier.height(6.dp))
                Text(d.method, style = MaterialTheme.typography.bodyLarge, color = p.ink)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("对照标准")
                sub.standard.lines().filter { it.isNotBlank() }.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("·", color = p.red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(14.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = p.ink)
                    }
                }
            }
            Divider()
            SectionLabel("练完记下来")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(vm.value, { vm.value = it }, width = 110.dp, decimal = true, placeholder = "数字")
                Text(d.metric.ifBlank { "这次的成绩" }, style = MaterialTheme.typography.bodyMedium, color = p.ink2, modifier = Modifier.weight(1f))
            }
            RuledTextField(vm.note, { vm.note = it }, minLines = 3, placeholder = "备注（可不填）：哪里没做到；也可以粘贴 Gemini 的分析")
            InkButton("练完了，记下来", onClick = { vm.record() })
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = p.page,
            title = { Text("还没记成绩就结束？", style = MaterialTheme.typography.titleLarge, color = p.ink) },
            text = { Text("专注时长会记下，但这次练习不算进专项练天数。", style = MaterialTheme.typography.bodyMedium, color = p.ink2) },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; endNow() }) { Text("结束", color = p.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续练", color = p.ink) } },
        )
    }
}

private fun fmt(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)

@Composable
private fun FeedbackView(d: Drill, fb: DrillFeedback, noNumber: Boolean) {
    val p = Paper.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        TianZiGeCell(done = true, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text("练完了", style = MaterialTheme.typography.headlineMedium, color = p.ink)
            Text(if (noNumber) "这次没记数字" else d.metric, style = MaterialTheme.typography.bodyMedium, color = p.ink2)
        }
    }
    if (noNumber || fb.current == null) {
        Hint("下次记一个数字，才能看出有没有进步。")
        return
    }
    val cur = fb.current ?: return
    val prev = fb.previous
    Column(
        Modifier.fillMaxWidth().background(p.redWash, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(cur), style = MaterialTheme.typography.displayMedium, color = p.red)
            if (prev != null) {
                Text("   上次 ${fmt(prev)}", style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        val verdict = when {
            prev == null -> "第一次记录，这就是起点。"
            fb.isBest -> "最近最好的一次。"
            cur == prev -> "和上次持平。"
            (cur > prev) != fb.lowerIsBetter -> "比上次好。"
            else -> "比上次差。看看是哪条标准没做到。"
        }
        Text(verdict, style = MaterialTheme.typography.titleSmall, color = p.ink)
        fb.best?.let { Text("最近最好：${fmt(it)}" + if (fb.lowerIsBetter) "（越低越好）" else "", style = MaterialTheme.typography.labelMedium, color = p.ink2) }
        if (fb.history.size >= 2) Sparkline(fb.history)
    }
}
