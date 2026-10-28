package com.yishou.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yishou.app.data.Echo
import com.yishou.app.data.EchoKind
import com.yishou.app.line.Ask
import com.yishou.app.line.Beat
import com.yishou.app.line.LineState
import com.yishou.app.line.TalkRules
import com.yishou.app.system.Variant
import com.yishou.app.think.Logic
import kotlinx.coroutines.delay

/*
 * 岔路的几样界面零件：
 * - 刹车按钮：不可逆的那一下（押答案、放行）先停 2 秒，再自动落下，期间点一下就收回；
 * - 逻辑词发亮：转折、限定、否定、条件、因果亮起来，其余暗下去；点一句钉到顶上的寄存器里；
 * - 回响卡：开局头几秒推到你面前的旧规则 / 差值；
 * - 先喷后砍：先不限字数地倒出来，再压成一句条件句。
 */

/** 停 2 秒再落下。主线（非岔路）照常一点就落。 */
@Composable
fun BrakeButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    brake: Boolean = Variant.chalu,
    onClick: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(onClick)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(armed) {
        if (!armed) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(BRAKE_MS, easing = LinearEasing))
        armed = false
        action()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (armed) {
            OutlinedButton(onClick = { armed = false }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("停 2 秒：条件都看了吗？（点一下收回）")
            }
            LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth())
        } else {
            Button(
                onClick = { if (brake) armed = true else action() },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(text) }
        }
    }
}

private const val BRAKE_MS = 2_000

@Composable
private fun logicColor(k: Logic.Kind): Color = when (k) {
    Logic.Kind.TURN -> MaterialTheme.colorScheme.error
    Logic.Kind.NEG -> MaterialTheme.colorScheme.error
    Logic.Kind.LIMIT -> MaterialTheme.colorScheme.tertiary
    Logic.Kind.COND -> MaterialTheme.colorScheme.primary
    Logic.Kind.CAUSE -> MaterialTheme.colorScheme.secondary
}

/**
 * 逻辑词发亮的一段字。[onPin] 不为空时，点哪儿就把那一小句钉上去。
 */
@Composable
fun LogicText(text: String, style: TextStyle, modifier: Modifier = Modifier, onPin: ((String) -> Unit)? = null) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = Logic.Kind.entries.associateWith { logicColor(it) }
    val annotated = remember(text, colors, dim) {
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(color = dim), 0, text.length)
            Logic.marks(text).forEach { m ->
                addStyle(SpanStyle(color = colors.getValue(m.kind), fontWeight = FontWeight.Bold), m.start, m.end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pin by rememberUpdatedState(onPin)
    Text(
        annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            if (onPin == null) Modifier
            else Modifier.pointerInput(text) {
                detectTapGestures { p ->
                    val l = layout ?: return@detectTapGestures
                    val clause = Logic.clauseAt(text, l.getOffsetForPosition(p))
                    if (clause.isNotBlank()) pin?.invoke(clause)
                }
            },
        ),
    )
}

/** 顶上的外置寄存器：最多 3 格，钉住的条件不随材料收起而消失；点一格取下 */
@Composable
fun RegisterSlots(pinned: List<String>, onUnpin: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (pinned.isEmpty()) "已锁定条件（点材料里的一句钉上来，最多 3 条）" else "已锁定条件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            (0 until MAX_SLOTS).forEach { i ->
                val t = pinned.getOrNull(i)
                Text(
                    "${"①②③"[i]} ${t ?: "＿＿＿"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = if (t == null) Modifier else Modifier.clickable { onUnpin(i) },
                )
            }
        }
    }
}

const val MAX_SLOTS = 3

/** 钉一条：已有的不重复；满了挤掉最早的 */
fun pinClause(pinned: List<String>, clause: String): List<String> {
    val c = clause.trim().take(40)
    if (c.isEmpty() || c in pinned) return pinned
    return (pinned + c).takeLast(MAX_SLOTS)
}

/**
 * 回响卡：上次你自己写下的规则 / 差值 / 结论，开局先过一遍。
 * 念一遍（对上一半以上）或者点“记得”，才往下走。
 */
@Composable
fun EchoCard(echo: Echo, onConfirm: (said: String) -> Unit) {
    var said by rememberSaveable(echo.id) { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(EchoKind.label(echo.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("「${echo.text}」", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("先把它念一遍，再往下走。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            VoiceTextField(said, { said = it }, "念出来（或者直接点记得）", Modifier.fillMaxWidth())
            Button(onClick = { onConfirm(said) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (said.isBlank()) "记得，接着" else "念完了，接着")
            }
        }
    }
}

fun beatLabel(s: LineState): String = when {
    s.ask == Ask.ANCHOR -> "回主干"
    s.ask != Ask.NORMAL -> "降一档"
    s.beat == Beat.TRAP -> "捉虫：叫停它，改一个条件"
    s.beat == Beat.FLIP -> "变条件：条件变了，结论怎么改"
    else -> "锁一句条件句"
}

/**
 * 先喷后砍：
 * ① 喷——想到什么说什么，不限字数、不管口水（语音最快）；
 * ② 砍——20 秒内压成 40 字以内的一句“只有当……，才……”。喷的原话里逻辑词亮着，方便挑出条件。
 */
@Composable
fun DumpCutBox(s: LineState, onCut: (raw: String, cut: String) -> Unit, onRefuse: () -> Unit, onPark: () -> Unit) {
    var raw by rememberSaveable(s.question, s.moves) { mutableStateOf("") }
    var cut by rememberSaveable(s.question, s.moves) { mutableStateOf("") }
    var cutting by rememberSaveable(s.question, s.moves) { mutableStateOf(false) }
    val small = s.ask != Ask.NORMAL && s.ask != Ask.ANCHOR
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.say.isNotBlank() && s.say != s.question) {
                Text(s.say.substringBefore(s.question).trim().removeSuffix("下一问：").removeSuffix("再说一次：").removeSuffix("再回答：").trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(beatLabel(s), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LogicText(s.question, MaterialTheme.typography.titleLarge)
            when {
                small -> {
                    // 降阶的小问题：不用喷也不用砍，一个词就行
                    VoiceTextField(cut, { cut = it }, "一个词、一个字都行", Modifier.fillMaxWidth())
                    Button(onClick = { onCut("", cut) }, enabled = cut.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("下这一手") }
                }
                !cutting -> {
                    Text("① 先喷：想到什么说什么，不限字数，口水也行。", style = MaterialTheme.typography.bodyMedium)
                    VoiceTextField(raw, { raw = it }, "全倒出来", Modifier.fillMaxWidth(), minLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { cutting = true }, enabled = raw.isNotBlank(), modifier = Modifier.weight(1f).height(50.dp)) { Text("喷完了，开始砍") }
                        TextButton(onClick = { cutting = true }) { Text("直接砍") }
                    }
                }
                else -> {
                    CutClock(key = s.question + s.moves)
                    if (raw.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            LogicText(raw, MaterialTheme.typography.bodySmall, Modifier.padding(10.dp), onPin = { cut = if (cut.isBlank()) it else cut })
                        }
                    }
                    VoiceTextField(cut, { cut = it }, "只有当＿＿，才＿＿", Modifier.fillMaxWidth(), minLines = 2)
                    if (cut.isNotBlank()) TalkMeter(cut, TalkRules.CUT)
                    if (s.busy) VortexLoader("教练在看这一手")
                    Button(onClick = { onCut(raw, cut) }, enabled = cut.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("下这一手") }
                    TextButton(onClick = { cutting = false }) { Text("回去再喷几句", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefuse, enabled = !s.busy) { Text("想不出") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onPark, enabled = !s.busy) { Text("先放着", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** 砍的倒计时：20 秒。到点不拦你，只是变红——下次快一点。 */
@Composable
private fun CutClock(key: String) {
    var left by remember(key) { mutableIntStateOf(CUT_SECONDS) }
    LaunchedEffect(key) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    Text(
        if (left > 0) "② 砍：$left 秒内压成一句条件句（40 字以内）" else "② 超时了，先交这一句，下次快一点",
        style = MaterialTheme.typography.bodyMedium,
        color = if (left > 5) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

private const val CUT_SECONDS = 20
