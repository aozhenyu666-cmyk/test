@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishou.app.coach.Busy
import com.yishou.app.coach.CoachPlan
import com.yishou.app.coach.CoachStats
import com.yishou.app.coach.Feedback
import com.yishou.app.coach.Warmup
import com.yishou.app.data.MoveKind
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.SetMove
import com.yishou.app.data.Task
import com.yishou.app.data.TrainSet
import com.yishou.app.llm.CoachPrompts
import com.yishou.app.llm.Faces
import com.yishou.app.system.Attachment
import com.yishou.app.system.Haptics
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import kotlin.random.Random

/*
 * 教练页的几个部件，主页和陪练窗口共用。
 * 一组只有三块：当前材料、结论链（一串棋子）、这一问。其余都折起来。
 */

fun busyLabel(b: Busy?): String? = when (b) {
    Busy.OPENING -> "教练在排这一组"
    Busy.JUDGING -> "教练在看你这一手"
    Busy.HELPING -> "教练在想怎么帮你"
    null -> null
}

fun kindLabel(m: SetMove): String = when (m.kind) {
    MoveKind.TRY -> "第 ${m.idx} 手"
    MoveKind.RELAY -> "第 ${m.idx} 手，接力"
    else -> "第 ${m.idx} 手，独立验证"
} + if (m.attempt > 1) "（第 ${m.attempt} 次）" else ""

// ---------------- 开组 ----------------

/**
 * 开一组：选领域（点一下就行，不用填表）、主练哪一面（教练建议，可以掷骰换）、材料（教练出，或者自己给）。
 */
@Composable
fun OpenSetPanel(
    tasks: List<Task>,
    suggestedFace: Int,
    warmupDefault: Boolean,
    shake: Boolean,
    stats: CoachStats?,
    attachment: Attachment?,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onStart: (domain: String, face: Int, material: String?, warmup: Boolean) -> Unit,
) {
    val current = tasks.firstOrNull { it.isCurrent }?.title
    val domains = (listOfNotNull(current) + DOMAIN_PRESETS + tasks.map { it.title }).distinct()
    var domain by rememberSaveable { mutableStateOf(current ?: DOMAIN_PRESETS.first()) }
    var custom by rememberSaveable { mutableStateOf("") }
    var face by rememberSaveable(suggestedFace) { mutableIntStateOf(suggestedFace) }
    var own by rememberSaveable { mutableStateOf(false) }
    var material by rememberSaveable { mutableStateOf("") }
    var warmup by rememberSaveable(warmupDefault) { mutableStateOf(warmupDefault) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("开一组", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "练什么、怎么开始、什么时候结束，由教练安排。你只面对眼前这一问。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label("领域")
        ChipRow {
            domains.forEach { d -> FilterChip(selected = domain == d && custom.isBlank(), onClick = { domain = d; custom = "" }, label = { Text(d) }) }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            singleLine = true,
            label = { Text("或者写一个新领域（例如：申论 归纳概括）") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Label("主练")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiceRoller(enabled = true, shake = shake, onRolled = { n -> face = if (n in CoachPlan.FACES) n else suggestedFace })
            Column(Modifier.weight(1f)) {
                Text("${Faces.of(face)?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(CoachPrompts.FACE_ACTIONS[face].orEmpty().substringAfter("："), style = MaterialTheme.typography.bodySmall)
            }
        }
        ChipRow {
            CoachPlan.FACES.forEach { n -> FilterChip(selected = face == n, onClick = { face = n }, label = { Text(Faces.of(n)?.short.orEmpty()) }) }
        }
        if (face == suggestedFace) {
            Text("这一面是教练建议的：等级最低、最近练得少，或者上一组没练稳。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }

        Label("材料")
        ChipRow {
            FilterChip(selected = !own, onClick = { own = false }, label = { Text("教练出") })
            FilterChip(selected = own, onClick = { own = true }, label = { Text("我来给") })
        }
        if (own) {
            VoiceTextField(material, { material = it }, "贴一段材料，或者说一下你正在做的事", Modifier.fillMaxWidth(), minLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onAttach, label = { Text("拍材料 / 相册") })
                Spacer(Modifier.width(8.dp))
                when (attachment) {
                    Attachment.Working -> Text("正在识别图片……", style = MaterialTheme.typography.bodySmall)
                    is Attachment.Ready -> TextButton(onClick = onClearAttachment) { Text("已识别，移除", maxLines = 1) }
                    is Attachment.Failed -> Text(attachment.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    null -> {}
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("先热身两分钟", style = MaterialTheme.typography.titleSmall)
                Text("一分钟呼吸，再做五步心算接龙。热身时教练同时在排这一组。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = warmup, onCheckedChange = { warmup = it })
        }
        Button(
            onClick = { onStart(custom.ifBlank { domain }, face, material.takeIf { own }, warmup) },
            enabled = attachment != Attachment.Working,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (warmup) "热身，然后开始" else "开始") }

        stats?.let { ProgressStrip(it) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

/** 最近两周的三个主数：独立完成、连续接力、回来复述。 */
@Composable
fun ProgressStrip(s: CoachStats) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近两周（${s.sets} 组）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Metric("独立完成", CoachStats.ratio(s.independent, s.verified))
                Metric("连续接力", CoachStats.ratio(s.relayFirst, s.relays))
                Metric("回来复述", CoachStats.ratio(s.recalled, s.recallTotal))
                Metric("改过就对", CoachStats.ratio(s.fixed, s.redone))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- 热身 ----------------

/**
 * 热身：像运动员先用轻量的同一个动作把身体活动开。
 * 第一段一分钟呼吸（吸 4 秒、呼 6 秒，圈跟着胀缩，手机跟着轻震），第二段五步心算接龙。
 * ready 表示教练已经把这一组排好了。
 */
@Composable
fun WarmupPanel(ready: Boolean, onDone: (String?) -> Unit) {
    var phase by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("热身", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when (phase) {
            0 -> Breathing(onDone = { phase = 1 })
            else -> ChainDrill(ready = ready, onDone = onDone)
        }
        TextButton(onClick = { if (phase == 0) phase = 1 else onDone(null) }) { Text(if (phase == 0) "跳过呼吸" else "跳过热身") }
        if (!ready) Text("教练在排这一组……", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Breathing(onDone: () -> Unit) {
    val context = LocalContext.current
    val still = reducedMotion()
    val scale = remember { Animatable(0.45f) }
    var inhale by remember { mutableStateOf(true) }
    var n by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        for (i in 1..Warmup.BREATHS) {
            n = i
            inhale = true
            Haptics.play(context, Haptics.Kind.INHALE)
            if (still) delay(Warmup.INHALE_MS) else scale.animateTo(1f, tween(Warmup.INHALE_MS.toInt(), easing = LinearEasing))
            inhale = false
            Haptics.play(context, Haptics.Kind.EXHALE)
            if (still) delay(Warmup.EXHALE_MS) else scale.animateTo(0.45f, tween(Warmup.EXHALE_MS.toInt(), easing = LinearEasing))
        }
        onDone()
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val r = size.minDimension / 2
            for (i in 1..3) drawCircle(ink.copy(alpha = 0.07f), r * i / 3f, style = Stroke(1f))
            drawCircle(gold.copy(alpha = 0.18f), r * scale.value)
            drawCircle(gold, r * scale.value, style = Stroke(3f))
        }
        Text(if (inhale) "吸气" else "呼气", style = MaterialTheme.typography.headlineSmall)
    }
    Text("第 $n / ${Warmup.BREATHS} 次。吸 4 秒，呼 6 秒，跟着圈走。", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ChainDrill(ready: Boolean, onDone: (String?) -> Unit) {
    val chain = remember { Warmup.chain(Random(System.currentTimeMillis())) }
    var i by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var right by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<String?>(null) }
    val started = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val done = i >= chain.steps.size
    val value = if (i == 0) chain.start else chain.steps[i - 1].result

    Text("心算接龙：每一步都用上一步的结果。", style = MaterialTheme.typography.bodyMedium)
    if (!done) {
        Text("$value  ${chain.steps[i].op}  =  ?", style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input,
            onValueChange = { v -> input = v.filter { it.isDigit() }.take(3) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("第 ${i + 1} / ${chain.steps.size} 步") },
            modifier = Modifier.width(200.dp),
        )
        Button(onClick = {
            val ok = input.toIntOrNull() == chain.steps[i].result
            if (ok) right++
            last = if (ok) "对" else "是 ${chain.steps[i].result}，从它接着来"
            input = ""
            i++
        }, enabled = input.isNotEmpty()) { Text("接上") }
        last?.let { Text(it, color = if (it == "对") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
    } else {
        val seconds = (System.currentTimeMillis() - started.longValue) / 1000
        val score = Warmup.score(right, chain.steps.size, seconds)
        Text(score, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(onClick = { onDone(score) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (ready) "开始这一组" else "教练还在排……")
        }
    }
}

// ---------------- 一组：材料、结论链、这一问 ----------------

/** 当前材料：今天练什么、材料、完成标准。默认展开，点一下收起。 */
@Composable
fun MaterialCard(set: TrainSet) {
    var open by rememberSaveable(set.id) { mutableStateOf(true) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DieFace(set.face, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(set.goal, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                Text(if (open) "收起" else "材料", style = MaterialTheme.typography.labelMedium)
            }
            if (open) {
                Text(set.material, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 26.sp))
                if (set.standard.isNotBlank()) Text(set.standard, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(set.material, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 结论链：每过一手落一颗子，旁边是压成短句的结论，竖着连成一串。
 * 最下面是还空着的那一颗，轻轻呼吸，等你落下这一手。
 */
@Composable
fun ChainTimeline(chain: List<String>, pending: Boolean, hidden: Boolean = false) {
    if (chain.isEmpty() && !pending) return
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Column {
        chain.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    Stone(filled = true, color = ink, number = i + 1, animateKey = "c$i$label")
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (hidden) "……" else label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Connector()
        }
        if (pending) {
            val still = reducedMotion()
            val a = if (still) 0.8f else rememberInfiniteTransition(label = "pending").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "a",
            ).value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(22.dp)) {
                        drawCircle(gold.copy(alpha = a), size.minDimension / 2 - 1.5f, style = Stroke(3f))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("这一手", style = MaterialTheme.typography.labelLarge, color = gold)
            }
        }
    }
}

@Composable
private fun Connector() {
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.width(28.dp).height(14.dp)) {
        drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
    }
}

/** 刚判完的那一手：过了、再改一下、这一步应该是、没说清。 */
@Composable
fun FeedbackLine(f: Feedback) {
    val (title, color) = when (f.verdict) {
        MoveVerdict.PASS -> "过了" to MaterialTheme.colorScheme.tertiary
        MoveVerdict.REDO -> "再改一下" to MaterialTheme.colorScheme.error
        MoveVerdict.SHOWN -> "这一步应该是" to MaterialTheme.colorScheme.secondary
        else -> "没说清" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 每次新的反馈都从下面轻轻浮上来
    val shown = remember(f) { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = shown, enter = fadeIn() + slideInVertically { it / 3 }) {
        Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (f.verdict == MoveVerdict.SHOWN && f.correctStep.isNotBlank()) Text(f.correctStep, style = MaterialTheme.typography.bodyMedium)
                if (f.gap.isNotBlank()) Text(f.gap, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 这一问：大号宋体。下面是给过的帮助。 */
@Composable
fun QuestionBlock(m: SetMove) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kindLabel(m), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(m.question, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 32.sp))
        if (m.helpText.isNotBlank()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(helpName(m.help), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(m.helpText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun helpName(level: Int) = when (level) {
    1 -> "线索"
    2 -> "半步"
    3 -> "示范"
    else -> "帮助"
}

/** 卡住时的三个入口：推不出（线索 → 半步 → 示范）、没看懂材料、直接看示范。 */
@Composable
fun HelpRow(m: SetMove, enabled: Boolean, onHint: () -> Unit, onExplain: () -> Unit, onDemo: () -> Unit) {
    val next = (m.help + 1).coerceAtMost(CoachPlan.MAX_LEVEL)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onHint, enabled = enabled && m.help < CoachPlan.MAX_LEVEL) { Text("推不出：给${helpName(next)}") }
            OutlinedButton(onClick = onExplain, enabled = enabled) { Text("没看懂") }
            if (m.help < CoachPlan.MAX_LEVEL) TextButton(onClick = onDemo, enabled = enabled) { Text("看示范") }
        }
        if (m.kind == MoveKind.VERIFY) {
            Text("独立验证：要帮助也可以，但这一手就不算独立完成。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 回来先复述：离开了一会儿，先不看结论链，凭记忆说出来，再对照、记一笔对上了几节。
 * 这是“切屏回来接不上”的直接练法。
 */
@Composable
fun RecallCard(chain: List<String>, awaySeconds: Long, onDone: (Int?) -> Unit) {
    var said by rememberSaveable { mutableStateOf("") }
    var revealed by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("回来了。先接上", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("离开了 ${awaySeconds / 60} 分钟。先别看，凭记忆说出刚才的结论链（一共 ${chain.size} 节）。", style = MaterialTheme.typography.bodyMedium)
            VoiceTextField(said, { said = it }, "刚才得出了什么", Modifier.fillMaxWidth(), minLines = 2, enabled = !revealed)
            if (!revealed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { revealed = true }) { Text("对照") }
                    TextButton(onClick = { onDone(null) }) { Text("跳过") }
                }
            } else {
                chain.forEachIndexed { i, c -> Text("${i + 1}. $c", style = MaterialTheme.typography.bodyLarge) }
                Text("对上了几节？", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    (0..chain.size).forEach { n -> AssistChip(onClick = { onDone(n) }, label = { Text("$n 节") }) }
                }
            }
        }
    }
}

/** 组末：这组走了几手、改了几次、用了几次帮助、独立验证有没有独立做对，再用一句话说练会了什么。 */
@Composable
fun SetSummary(set: TrainSet, moves: List<SetMove>, onSave: (String) -> Unit, onSkip: () -> Unit, extra: @Composable (lesson: String) -> Unit = {}) {
    var lesson by rememberSaveable(set.id) { mutableStateOf("") }
    val stepCount = moves.map { it.idx }.distinct().size
    val redos = moves.count { it.verdict == MoveVerdict.REDO }
    val helps = moves.count { it.help > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("这组练完了", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(set.goal, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Metric("走了", "$stepCount 手")
            Metric("改过", "$redos 次")
            Metric("帮助", "$helps 次")
            Metric("独立验证", when (set.verified) { 1 -> "独立"; 0 -> "未独立"; else -> "-" })
        }
        if (set.warmup.isNotBlank()) Text("热身：${set.warmup}", style = MaterialTheme.typography.bodySmall)
        ChainTimeline(CoachPlan.chain(moves), pending = false)
        VoiceTextField(lesson, { lesson = it }, "用一句话说这组练会了什么（会成为以后的回响）", Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(lesson) }, enabled = lesson.isNotBlank()) { Text("记下") }
            TextButton(onClick = onSkip) { Text("不写了") }
        }
        extra(lesson)
    }
}
