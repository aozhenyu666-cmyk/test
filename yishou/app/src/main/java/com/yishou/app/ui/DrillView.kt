package com.yishou.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yishou.app.data.Material
import com.yishou.app.think.Check
import com.yishou.app.think.DrillKey
import com.yishou.app.think.DrillState
import com.yishou.app.think.DrillStep
import com.yishou.app.think.Estimate
import com.yishou.app.think.Lens
import com.yishou.app.think.StanceItem
import com.yishou.app.ui.theme.reducedMotion

/** 一次练习：素材 → 问题 → 作答 → 揭晓 → 收口。 */
@Composable
fun DrillView(s: DrillState, vm: ThinkViewModel, onOtherLens: (Material?) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val steps = if (s.lens == Lens.STANCE) listOf("锁答案", "作答", "追问", "揭晓") else listOf("锁答案", "作答", "揭晓")
        val at = when (s.step) {
            DrillStep.LOADING -> 0
            DrillStep.ANSWER -> 1
            DrillStep.JUDGING -> if (s.lens == Lens.STANCE && s.counter.isNotEmpty()) 2 else 1
            DrillStep.COUNTER -> 2
            DrillStep.REVEAL -> steps.size - 1
        }
        BranchSteps(steps, at, Modifier.fillMaxWidth())
        s.material?.let { MaterialPanel(it, startOpen = s.step == DrillStep.ANSWER || s.step == DrillStep.LOADING) }
        val key = s.key
        when {
            s.error != null && key == null -> {
                Text(s.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.retry() }) { Text("重试") }
            }
            key == null -> MatrixLoader("先把标准定下来，再让你答")
            s.step == DrillStep.ANSWER -> AnswerStep(s, key, vm)
            s.step == DrillStep.JUDGING -> {
                Prompt(s.lens, key)
                MatrixLoader("对照锁住的答案，核对你的原话")
            }
            s.step == DrillStep.COUNTER -> CounterStep(s, vm)
            s.step == DrillStep.REVEAL -> RevealStep(s, key, vm, onOtherLens)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun MaterialPanel(m: Material, startOpen: Boolean) {
    var open by remember(startOpen) { mutableStateOf(startOpen) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { open = !open },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(m.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 60 else 2)
        }
    }
}

@Composable
private fun Prompt(lens: Lens, key: DrillKey) {
    when (lens) {
        Lens.ASSUME -> {
            Text("「${key.prompt}」", style = MaterialTheme.typography.titleMedium)
            Text(lens.ask, style = MaterialTheme.typography.bodyLarge)
        }
        Lens.GIST -> Text(lens.ask, style = MaterialTheme.typography.titleMedium)
        Lens.ESTIMATE -> {
            Text(key.prompt, style = MaterialTheme.typography.titleMedium)
            Text(lens.ask + (if (key.unit.isNotEmpty()) "（单位：${key.unit}）" else ""), style = MaterialTheme.typography.bodyMedium)
        }
        else -> Text(key.prompt, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerStep(s: DrillState, key: DrillKey, vm: ThinkViewModel) {
    Prompt(s.lens, key)
    when (s.lens) {
        Lens.GIST, Lens.ASSUME -> {
            var text by rememberSaveable(key.prompt) { mutableStateOf(s.answer) }
            VoiceTextField(text, { text = it }, if (s.lens == Lens.GIST) "它最想让人相信的是……" else "它默认了……", Modifier.fillMaxWidth(), minLines = 3)
            Notes(s)
            Button(onClick = { vm.answer(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("交卷") }
        }
        Lens.STANCE -> {
            val parts = StanceItem.entries.associateWith { item ->
                var v by rememberSaveable(key.prompt, item) { mutableStateOf(s.parts[item].orEmpty()) }
                VoiceTextField(v, { v = it }, "${item.label}：${item.hint}", Modifier.fillMaxWidth(), minLines = if (item == StanceItem.SIDE) 1 else 2)
                v
            }
            Notes(s)
            Button(onClick = { vm.stance(parts) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("交卷") }
        }
        Lens.ESTIMATE -> {
            var low by rememberSaveable(key.prompt) { mutableStateOf("") }
            var high by rememberSaveable(key.prompt) { mutableStateOf("") }
            var said by rememberSaveable(key.prompt) { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(low, { low = it }, label = { Text("最低") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                Text("到")
                OutlinedTextField(high, { high = it }, label = { Text("最高") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            VoiceTextField(said, { said = it }, "或者直接说：三千到五千", Modifier.fillMaxWidth())
            Text("区间要让你有九成把握：太窄容易落空，太宽没意义。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Notes(s)
            val typed = low.toDoubleOrNull()?.let { l -> high.toDoubleOrNull()?.let { h -> l to h } }
            val range = typed ?: Estimate.range(said)
            Button(
                onClick = { range?.let { vm.range(it.first, it.second) } },
                enabled = range != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(range?.let { "交：${Estimate.show(it.first)} – ${Estimate.show(it.second)}" } ?: "交卷") }
        }
        Lens.PREDICT -> {
            var claim by rememberSaveable(key.prompt) { mutableStateOf("") }
            var conf by rememberSaveable(key.prompt) { mutableIntStateOf(0) }
            VoiceTextField(claim, { claim = it }, "我押：到……时候，会……", Modifier.fillMaxWidth(), minLines = 2)
            Text("把握", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50, 60, 70, 80, 90, 100).forEach { c -> FilterChip(selected = conf == c, onClick = { conf = c }, label = { Text("$c") }) }
            }
            Notes(s)
            Button(onClick = { vm.predict(claim, conf) }, enabled = claim.isNotBlank() && conf > 0, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("押下") }
        }
    }
}

@Composable
private fun Notes(s: DrillState) {
    s.thin?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun CounterStep(s: DrillState, vm: ThinkViewModel) {
    s.key?.let { Prompt(s.lens, it) }
    Checks(s.checks)
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("追问一句", style = MaterialTheme.typography.labelLarge)
            Text(s.counter, style = MaterialTheme.typography.bodyLarge)
        }
    }
    var reply by rememberSaveable(s.counter) { mutableStateOf(s.reply) }
    VoiceTextField(reply, { reply = it }, "你怎么接这一句？", Modifier.fillMaxWidth(), minLines = 2)
    Notes(s)
    Text("只追问这一轮，不会一直聊下去。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { vm.reply(reply) }, enabled = reply.isNotBlank(), modifier = Modifier.weight(1f)) { Text("回应") }
        OutlinedButton(onClick = { vm.skipReply() }, modifier = Modifier.weight(1f)) { Text("先不回应") }
    }
}

@Composable
private fun RevealStep(s: DrillState, key: DrillKey, vm: ThinkViewModel, onOtherLens: (Material?) -> Unit) {
    Prompt(s.lens, key)
    if (s.score >= 0) ScoreRing(s.score) else {
        Text("押下了，记进了预判本。", style = MaterialTheme.typography.headlineSmall)
        Text("这一条不由模型判，等现实。到了时候去 主控台 → 预判本 里点中没中。", style = MaterialTheme.typography.bodyMedium)
    }
    if (s.answer.isNotBlank() && s.lens != Lens.STANCE) {
        Text("你答的：${s.answer}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Checks(s.checks)
    LockedKey(s, key)

    var note by rememberSaveable(s.rowId) { mutableStateOf(s.takeaway) }
    Text("收口", style = MaterialTheme.typography.labelLarge)
    VoiceTextField(note, { note = it }, "一句话：下次遇到这类东西，先做什么", Modifier.fillMaxWidth())
    if (note.isNotBlank() && note.trim() != s.takeaway) {
        TextButton(onClick = { vm.takeaway(note) }) { Text("记下这句") }
    } else if (s.takeaway.isNotEmpty()) {
        Text("记下了。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s.material != null) {
            Button(onClick = { onOtherLens(s.material) }, modifier = Modifier.weight(1f)) { Text("换个练法") }
        } else {
            Button(onClick = { vm.begin(null, Lens.ESTIMATE) }, modifier = Modifier.weight(1f)) { Text("再估一个") }
        }
        OutlinedButton(onClick = { onOtherLens(null) }, modifier = Modifier.weight(1f)) { Text("回素材库") }
    }
    if (s.score >= 0) {
        TextButton(onClick = { vm.dispute() }) {
            Text(if (s.disputed) "已标记：答案有问题（这次不计分）· 撤销" else "觉得锁住的答案本身有问题？标记一下，这次不计分")
        }
    }
}

/** 分数：一圈慢慢画满 */
@Composable
private fun ScoreRing(score: Int) {
    val still = reducedMotion()
    val p = remember { Animatable(0f) }
    LaunchedEffect(score) { if (still) p.snapTo(score / 100f) else p.animateTo(score / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val w = 6.dp.toPx()
                drawArc(faint, -90f, 360f, false, style = Stroke(w))
                drawArc(if (score >= 60) gold else ink, -90f, 360f * p.value, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text("${(p.value * 100).toInt()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            when {
                score >= 90 -> "说到点子上了。"
                score >= 60 -> "大方向对，还差一点。"
                score > 0 -> "沾到边了，看看差在哪。"
                else -> "这次没说中。对照一下锁住的答案。"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Checks(checks: List<Check>) {
    if (checks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (c.ok) "✓" else "✗",
                    color = if (c.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.labelLarge)
                    if (c.ok && c.quote.isNotBlank()) Text("你说的：「${c.quote}」", style = MaterialTheme.typography.bodyMedium)
                    if (!c.ok && c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 揭晓：作答前就锁住的那份答案 */
@Composable
private fun LockedKey(s: DrillState, key: DrillKey) {
    val lines: List<String> = when (s.lens) {
        Lens.GIST -> listOf("主干：${key.points.firstOrNull().orEmpty()}") + key.points.drop(1).map { "要点：$it" }
        Lens.ASSUME -> key.points.map { "前提：$it" }
        Lens.STANCE -> key.points.mapIndexed { i, p -> (if (i == 0) "正方最强：" else "反方最强：") + p }
        Lens.ESTIMATE -> listOfNotNull(
            key.value?.let { "参考值：${Estimate.show(it)}${key.unit}（两次独立估算对得上才出的题）" },
            key.method.takeIf { it.isNotBlank() }?.let { "怎么拆：$it" },
        )
        Lens.PREDICT -> emptyList()
    }
    if (lines.isEmpty() && key.explain.isBlank()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("锁住的答案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (key.explain.isNotBlank()) Text(key.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
