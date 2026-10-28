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
import androidx.compose.ui.text.withStyle
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
        val steps = when (s.lens) {
            Lens.STANCE -> listOf("锁答案", "作答", "追问", "揭晓")
            Lens.RECITE -> listOf("看", "复述", "对照")
            else -> listOf("锁答案", "作答", "揭晓")
        }
        val at = when (s.step) {
            DrillStep.LOADING -> 0
            DrillStep.ANSWER -> if (s.lens == Lens.RECITE && !s.hidden) 0 else 1
            DrillStep.JUDGING, DrillStep.SELF -> if (s.lens == Lens.STANCE && s.counter.isNotEmpty()) 2 else 1
            DrillStep.COUNTER -> 2
            DrillStep.REVEAL -> steps.size - 1
        }
        BranchSteps(steps, at, Modifier.fillMaxWidth())
        val m = s.material
        when {
            s.lens == Lens.RECITE -> {}
            m != null && s.hidden -> HiddenPanel(m)
            m != null -> MaterialPanel(m, startOpen = s.step == DrillStep.ANSWER || s.step == DrillStep.LOADING)
        }
        val key = s.key
        when {
            s.error != null && key == null -> {
                Text(s.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.retry() }) { Text("重试") }
            }
            key == null -> VortexLoader("先把标准定下来，再让你答")
            s.step == DrillStep.ANSWER -> AnswerStep(s, key, vm)
            s.step == DrillStep.JUDGING -> {
                Prompt(s.lens, key)
                VortexLoader("对照锁住的答案，核对你的原话")
            }
            s.step == DrillStep.SELF -> SelfStep(s, key, vm)
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
        Lens.GIST, Lens.FRAME, Lens.ECHO -> Text(lens.ask, style = MaterialTheme.typography.titleMedium)
        Lens.RECITE -> Text(key.prompt, style = MaterialTheme.typography.titleMedium)
        Lens.LINK -> {
            Text("「${key.prompt}」", style = MaterialTheme.typography.titleMedium)
            Text("它像你见过的什么？说出像什么、相同在哪、不同在哪。", style = MaterialTheme.typography.bodyLarge)
        }
        Lens.ESSAY -> {
            Text(key.prompt, style = MaterialTheme.typography.titleMedium)
            key.value?.let { Text("字数：不超过 ${it.toInt()} 字", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
    if (s.lens == Lens.RECITE) {
        ReciteStep(s, key, vm)
        return
    }
    Prompt(s.lens, key)
    when (s.lens) {
        Lens.RECITE -> {}
        Lens.LINK -> {
            val parts = com.yishou.app.think.LinkItem.entries.mapIndexed { i, item ->
                var v by rememberSaveable(key.prompt, item) { mutableStateOf(s.linkParts.getOrElse(i) { "" }) }
                VoiceTextField(v, { v = it }, "${item.label}：${item.hint}", Modifier.fillMaxWidth(), minLines = if (item == com.yishou.app.think.LinkItem.LIKE) 1 else 2)
                v
            }
            Notes(s)
            Button(onClick = { vm.link(parts) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("交卷") }
        }
        Lens.GIST, Lens.ASSUME, Lens.FRAME, Lens.ECHO -> {
            var text by rememberSaveable(key.prompt) { mutableStateOf(s.answer) }
            val hint = when (s.lens) {
                Lens.GIST -> "它最想让人相信的是……"
                Lens.FRAME -> "一行一条：A 和 B——什么关系"
                Lens.ECHO -> "我记得这段讲的是……"
                else -> "它默认了……"
            }
            VoiceTextField(text, { text = it }, hint, Modifier.fillMaxWidth(), minLines = 3)
            Notes(s)
            Button(onClick = { vm.answer(text) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("交卷") }
        }
        Lens.ESSAY -> {
            var text by rememberSaveable(key.prompt) { mutableStateOf(s.answer) }
            VoiceTextField(text, { text = it }, "按题目要求作答", Modifier.fillMaxWidth(), minLines = 6)
            val n = com.yishou.app.think.Words.norm(text).length
            val limit = key.value?.toInt()
            Text(
                if (limit != null) "$n / $limit 字" else "$n 字",
                style = MaterialTheme.typography.labelMedium,
                color = if (limit != null && n > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    s.recite?.let { ReciteDiff(it) }
    if (s.answer.isNotBlank() && s.lens != Lens.STANCE && s.lens != Lens.RECITE) {
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
        if (s.lens == Lens.RECITE) {
            Button(onClick = { vm.recite(s.material, key.prompt, key.points.first(), s.skeleton) }, modifier = Modifier.weight(1f)) { Text("再背一遍") }
        } else if (s.material != null) {
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
        Lens.ESSAY -> key.points.mapIndexed { i, p -> "要点 ${i + 1}：$p" }
        Lens.ECHO -> listOf("主干：${key.points.firstOrNull().orEmpty()}") + key.points.drop(1).map { "要点：$it" }
        Lens.FRAME -> key.points.map { "关系：$it" }
        Lens.LINK -> key.points.map { "参考：$it" }
        Lens.RECITE -> listOf("原文：${key.points.firstOrNull().orEmpty()}")
    }
    if (lines.isEmpty() && key.explain.isBlank()) return
    CloudReveal(key.prompt) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("锁住的答案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (key.explain.isNotBlank()) Text(key.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

/** 判卷没成功：对着锁住的答案自己逐条打勾，不卡在这里 */
@Composable
private fun SelfStep(s: DrillState, key: DrillKey, vm: ThinkViewModel) {
    Prompt(s.lens, key)
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
    if (s.answer.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text("你答的：${s.answer}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
    }
    val texts: List<String> = when (s.lens) {
        Lens.STANCE -> listOf(
            "明确站一边（“都有道理”不算）",
            "针对这个问题的具体理由（空话不算）",
            "真正对你不利的最强一条，不是稻草人",
            "一个看得见、能核对的条件",
        )
        Lens.LINK -> com.yishou.app.think.LinkItem.entries.map { it.standard }
        else -> key.points
    }
    var marks by remember(s.checks.size) { mutableStateOf(List(s.checks.size) { false }) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        s.checks.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().clickable { marks = marks.toMutableList().also { it[i] = !it[i] } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(checked = marks.getOrElse(i) { false }, onCheckedChange = { v -> marks = marks.toMutableList().also { it[i] = v } })
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.labelLarge)
                    texts.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    Text("只勾你真的写出来了的。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { vm.selfMark(marks) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("按我勾的算分") }
}


/** 回想：原文收着，揭晓时再给看 */
@Composable
private fun HiddenPanel(m: Material) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = MaterialTheme.typography.labelLarge)
            Text("原文收着，交卷后再看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 背下来：先看（倒计时，可以提前点“记住了”），时间到原文一个字一个字散掉，
 * 然后复述。收起之后离开一手会被记下来。
 */
@Composable
private fun ReciteStep(s: DrillState, key: DrillKey, vm: ThinkViewModel) {
    val text = key.points.firstOrNull().orEmpty()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.left() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.readUntil, s.hidden) {
        while (!s.hidden) {
            now = System.currentTimeMillis()
            if (now >= s.readUntil) {
                vm.hideRecite()
                break
            }
            kotlinx.coroutines.delay(250)
        }
    }
    Text(key.prompt, style = MaterialTheme.typography.titleMedium)
    val fade = remember(key.prompt) { Animatable(1f) }
    val still = reducedMotion()
    LaunchedEffect(s.hidden) {
        if (s.hidden) { if (still) fade.snapTo(0f) else fade.animateTo(0f, tween(1_400)) } else fade.snapTo(1f)
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fade.value > 0f) {
                DissolveText(text, fade.value)
            } else if (s.skeleton) {
                Text(com.yishou.app.think.Recite.skeleton(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("原文已收起。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!s.hidden) {
        val left = ((s.readUntil - now) / 1000).coerceAtLeast(0)
        Text("还能看 $left 秒", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        Button(onClick = { vm.hideRecite() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("记住了，收起来") }
        return
    }
    var said by rememberSaveable(key.prompt) { mutableStateOf("") }
    VoiceTextField(said, { said = it }, "复述出来（说也行）", Modifier.fillMaxWidth(), minLines = 4)
    if (s.leaves > 0) Text("收起后离开过一手 ${s.leaves} 次，会记在结果里。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    Notes(s)
    Button(onClick = { vm.said(said) }, enabled = said.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("对照") }
}

/** 一个字一个字地散掉：每个字有自己的消失时刻，散到最后只剩原文的轮廓 */
@Composable
private fun DissolveText(text: String, progress: Float) {
    val ink = MaterialTheme.colorScheme.onSurface
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        text.forEachIndexed { i, c ->
            // 每个字的“消失点”在 0–0.6 之间散开，看起来是一片一片地淡掉
            val seed = ((i * 7919) % 97) / 97f * 0.6f
            val a = ((progress - seed) / 0.4f).coerceIn(0f, 1f)
            withStyle(androidx.compose.ui.text.SpanStyle(color = ink.copy(alpha = 0.08f + 0.92f * a))) { append(c) }
        }
    }
    Text(annotated, style = MaterialTheme.typography.titleMedium)
}

/** 逐字对照：对上的正常显示，漏掉的标红加下划线，多说的标灰加删除线 */
@Composable
private fun ReciteDiff(r: com.yishou.app.think.Recite.Result) {
    val ink = MaterialTheme.colorScheme.onSurface
    val miss = MaterialTheme.colorScheme.error
    val extra = MaterialTheme.colorScheme.outline
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        r.pieces.forEach { p ->
            val style = when (p.op) {
                com.yishou.app.think.Recite.Op.SAME, com.yishou.app.think.Recite.Op.MARK -> androidx.compose.ui.text.SpanStyle(color = ink)
                com.yishou.app.think.Recite.Op.MISS -> androidx.compose.ui.text.SpanStyle(color = miss, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                com.yishou.app.think.Recite.Op.EXTRA -> androidx.compose.ui.text.SpanStyle(color = extra, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            }
            withStyle(style) { append(p.text) }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(annotated, style = MaterialTheme.typography.titleMedium)
            Text("红色下划线：漏了；灰色删除线：多说或说错的。对上 ${r.matched}/${r.total} 字。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
