@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.yishou.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yishou.app.card.Card
import com.yishou.app.card.Conf
import com.yishou.app.card.Flaws
import com.yishou.app.card.Round
import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import kotlinx.coroutines.delay

/** 题卡上的所有操作，三个地方（主页、窗口、思考页）共用 */
class CardActions(
    val bet: (Int, Conf) -> Unit,
    val evidence: (Set<Int>) -> Unit,
    val check: (Int, String) -> Unit,
    val change: (Int?) -> Unit,
    val hint: () -> Unit,
    val cause: (String) -> Unit,
    val key: (Int?) -> Unit,
    val hide: () -> Unit,
    val note: (String) -> Unit,
    /** 先说再选：用自己的话说的答案 */
    val predict: (String) -> Unit = {},
)

private fun stepsOf(r: Round): List<String> = buildList {
    add("下注")
    if (r.card.hasEvidence) add("依据")
    if (r.cfg.checks > 0) add("多查")
    add("揭晓")
}

private fun stepIndex(r: Round): Int {
    val ev = if (r.card.hasEvidence) 1 else 0
    return when (r.step) {
        Step.BET -> 0
        Step.EVIDENCE -> 1
        Step.CHECK, Step.CHANGE -> 1 + ev
        Step.REVEAL -> stepsOf(r).size - 1
    }
}

/**
 * 一张题卡：上面一根枝条标着走到哪一步，中间是材料和问题，下面只放这一步要做的事。
 * 判对错只和题卡比对，揭晓时一行记分。
 */
@Composable
fun CardRoundView(s: SessionState, a: CardActions) {
    val r = s.round ?: return
    val card = r.card
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.animateContentSize()) {
        BranchSteps(stepsOf(r), stepIndex(r), Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.type.ifBlank { card.skill.label }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            val row = s.dealt?.row
            if (row != null && !row.verified && row.source == com.yishou.app.data.CardSource.AI) {
                Spacer(Modifier.width(8.dp))
                Text("未核对：答案只作参考，不计分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.dealt?.redo == true) {
                Spacer(Modifier.width(8.dp))
                Text("错题重做", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            if (s.size > 1) Text("第 ${s.index + if (r.step == Step.REVEAL) 0 else 1} / ${s.size} 张", style = MaterialTheme.typography.labelMedium)
        }

        // 材料：同屏、或看完收起
        var picked by remember(card) { mutableStateOf(setOf<Int>()) }
        // 岔路：顶上的外置寄存器，钉住的条件材料收起后还在
        var pinned by remember(card) { mutableStateOf(listOf<String>()) }
        val chalu = com.yishou.app.system.Variant.chalu && card.sentences.isNotEmpty()
        if (chalu) RegisterSlots(pinned, onUnpin = { i -> pinned = pinned.filterIndexed { j, _ -> j != i } })
        val pin: ((String) -> Unit)? = if (chalu && r.step != Step.EVIDENCE) ({ c: String -> pinned = pinClause(pinned, c) }) else null
        val canHide = s.level >= 2 && r.step == Step.BET && !s.hidden && card.sentences.isNotEmpty()
        if (canHide) ReadTimer(card, s.level, onHide = a.hide)
        if (s.hidden && r.step != Step.REVEAL) {
            HiddenPassage(card, s, picked, pickable = r.step == Step.EVIDENCE) { n -> picked = toggle(picked, n) }
        } else {
            Passage(card, r, picked, pickable = r.step == Step.EVIDENCE, onPin = pin) { n -> picked = toggle(picked, n) }
        }
        if (s.level == 2 && !s.hidden && r.step == Step.BET) {
            OutlinedTextField(
                value = s.notes,
                onValueChange = { a.note(it.take(20)) },
                singleLine = true,
                label = { Text("记几个字，收起后还能看到") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(card.question, style = MaterialTheme.typography.titleMedium)

        when (r.step) {
            Step.BET -> BetStep(card, s, a)
            Step.EVIDENCE -> {
                Text("依据是哪一句？在材料里点出来（最多两句）。", style = MaterialTheme.typography.bodyMedium)
                Options(card, r, selected = r.bet, marks = false)
                Button(onClick = { a.evidence(picked) }, enabled = picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(if (picked.isEmpty()) "点出依据句" else "依据是 ${picked.sorted().joinToString("、") { Card.mark(it) }}")
                }
            }
            Step.CHECK -> CheckStep(card, r, a)
            Step.CHANGE -> {
                Text("查完了。还选 ${Card.letter(r.bet ?: 0)} 吗？", style = MaterialTheme.typography.bodyLarge)
                Options(card, r, selected = r.bet, marks = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.change(null) }) { Text("不改") }
                    (0..3).filter { it != r.bet }.forEach { o -> OutlinedButton(onClick = { a.change(o) }) { Text("改 ${Card.letter(o)}") } }
                }
            }
            Step.REVEAL -> if (s.needsKey) KeyStep(a) else Reveal(card, r, a)
        }

        if (r.step != Step.REVEAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = a.hint) {
                    Text(when (r.help) { 0 -> "卡住了：指一下该看哪"; 1 -> "还是不会：排除两项"; else -> "看解析（这张不算自己完成）" })
                }
            }
            r.hintText()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

private fun toggle(set: Set<Int>, n: Int): Set<Int> = when {
    n in set -> set - n
    set.size >= 2 -> setOf(n)
    else -> set + n
}

/** 阶梯 2、3：读材料的时间，到点自动收起（也可以提前收起） */
@Composable
private fun ReadTimer(card: Card, level: Int, onHide: () -> Unit) {
    val total = 6 + card.sentences.sumOf { it.length } / 12
    var left by remember(card) { mutableIntStateOf(total) }
    LaunchedEffect(card) {
        while (left > 0) {
            delay(1_000)
            left--
        }
        onHide()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (level == 2) "记住材料，$left 秒后收起（可以记几个字）" else "记住材料，$left 秒后收起，收起后不能看",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onHide) { Text("记住了") }
    }
}

@Composable
private fun Passage(card: Card, r: Round, picked: Set<Int>, pickable: Boolean, onPin: ((String) -> Unit)? = null, onPick: (Int) -> Unit) {
    if (card.sentences.isEmpty()) return
    val reveal = r.step == Step.REVEAL
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            card.sentences.forEachIndexed { i, sentence ->
                val n = i + 1
                val chosen = n in picked || (reveal && n in r.evidence)
                val key = reveal && n in card.evidence
                val bg by animateColorAsState(
                    when {
                        key -> MaterialTheme.colorScheme.tertiaryContainer
                        chosen -> MaterialTheme.colorScheme.secondaryContainer
                        else -> Color.Transparent
                    },
                    label = "bg",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (pickable) Modifier.clickable { onPick(n) } else Modifier)
                        .padding(vertical = 2.dp),
                ) {
                    Surface(color = bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Text(Card.mark(n), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(22.dp))
                            if (com.yishou.app.system.Variant.chalu) LogicText(sentence, MaterialTheme.typography.bodyLarge, onPin = onPin)
                            else Text(sentence, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

/** 材料收起以后：只剩句子编号（和阶梯 2 记下的几个字） */
@Composable
private fun HiddenPassage(card: Card, s: SessionState, picked: Set<Int>, pickable: Boolean, onPick: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("材料已收起，凭记忆做。", style = MaterialTheme.typography.labelLarge)
            if (s.level == 2 && s.notes.isNotBlank()) Text("你记下的：${s.notes}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..card.sentences.size).forEach { n ->
                    FilterChip(selected = n in picked, onClick = { if (pickable) onPick(n) }, label = { Text("第 ${Card.mark(n)} 句") })
                }
            }
        }
    }
}

@Composable
private fun BetStep(card: Card, s: SessionState, a: CardActions) {
    var option by remember(card) { mutableStateOf<Int?>(null) }
    var conf by remember(card) { mutableStateOf<Conf?>(null) }
    s.rushNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    // 先说再选：选项先藏着。说中了直接选；说套话先被追问两刀
    if (s.mustPredict && s.predicted == null) {
        val chasing = s.drillAsk != null
        var said by remember(card, s.drillStep, chasing) { mutableStateOf("") }
        val pulse = idlePulse(
            said,
            if (chasing) listOf("先抄几个字也行，别空着。") else listOf("先只说：题目问的是什么？", "说出你觉得最关键的一个词也行。"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!chasing) {
                Text("先别看选项：正确答案必须长什么样？用自己的话说一句。", style = MaterialTheme.typography.bodyMedium)
                Text("说中了，选项一亮直接选；说“不知道”“差不多”这类，要先被追问两刀。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("追问 ${s.drillStep + 1}/${s.drillTotal}：${s.drillAsk}", style = MaterialTheme.typography.titleSmall)
                s.drillHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            pulse?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            VoiceTextField(said, { said = it }, if (chasing) "这一刀" else "正确答案应该……", Modifier.fillMaxWidth())
            Button(onClick = { a.predict(said) }, enabled = said.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (chasing) "交这一刀" else "说好了")
            }
        }
        return
    }
    // 读完再选：按材料长短锁几秒
    // 说中靶心的不用再等：想透了就该更快
    val unlockAt = if (s.mode == com.yishou.app.data.CardMode.GATE || s.bullseye) 0L else s.cardStartedAt + com.yishou.app.card.AntiRandom.minReadMs(card)
    var now by remember(card) { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(unlockAt) {
        while (now < unlockAt) {
            kotlinx.coroutines.delay(250)
            now = System.currentTimeMillis()
        }
    }
    val locked = now < unlockAt
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.predicted?.let { p ->
            Text("你先说的：$p", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (s.bullseye) Text("靶心对了。选出来就过。", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            else com.yishou.app.card.AntiRandom.closest(p, card)?.let { i ->
                Text("最接近 ${Card.letter(i)}。对照一下再定。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (locked) Text("先读完：${((unlockAt - now) / 1000) + 1} 秒后能选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.options.forEachIndexed { i, o -> OptionRow(i, o, selected = option == i, enabled = !locked, onClick = { if (!locked) option = i }) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("把握", style = MaterialTheme.typography.labelLarge)
            Conf.entries.forEach { c -> FilterChip(selected = conf == c, onClick = { conf = c }, label = { Text(c.label) }) }
        }
        // 押下去不可逆：岔路里先停 2 秒
        BrakeButton(
            when {
                option == null -> "先选一项"
                conf == null -> "再给个把握"
                else -> "押 ${Card.letter(option!!)}，把握${conf!!.label}"
            },
            enabled = option != null && conf != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val o = option
            val c = conf
            if (o != null && c != null) a.bet(o, c)
        }
    }
}

@Composable
private fun CheckStep(card: Card, r: Round, a: CardActions) {
    var target by remember(r.checks.size) { mutableStateOf(r.checkable.firstOrNull()) }
    var flaw by remember(r.checks.size) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("多查一步（${r.checks.size + 1}/${r.cfg.checks}）：你没选的里挑一项，说它错在哪。", style = MaterialTheme.typography.bodyMedium)
        card.options.forEachIndexed { i, o ->
            val done = r.checks[i]
            OptionRow(
                i, o,
                selected = target == i,
                enabled = i in r.checkable,
                tag = when {
                    i == r.bet -> "你押的"
                    done != null -> done
                    else -> null
                },
                onClick = { if (i in r.checkable) target = i },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Flaws.CHOICES.forEach { f -> FilterChip(selected = flaw == f, onClick = { flaw = f }, label = { Text(f) }) }
        }
        Button(
            onClick = {
                val o = target
                val f = flaw
                if (o != null && f != null) a.check(o, f)
            },
            enabled = target != null && flaw != null,
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (target != null && flaw != null) "${Card.letter(target!!)}：$flaw" else "选一项，再选错因") }
    }
}

/** 拍下的题：先点官方答案，才算分 */
@Composable
private fun KeyStep(a: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("这是拍下的题。书上的答案是？", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..3).forEach { o -> OutlinedButton(onClick = { a.key(o) }) { Text(Card.letter(o)) } }
        }
        TextButton(onClick = { a.key(null) }) { Text("没有官方答案（这张不计分）") }
    }
}

@Composable
private fun Reveal(card: Card, r: Round, a: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = if (r.firstCorrect) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("答案 ${Card.letter(card.answer)}", style = MaterialTheme.typography.headlineSmall)
                Text(r.scoreLine(), style = MaterialTheme.typography.titleSmall)
            }
        }
        Options(card, r, selected = r.finalAnswer, marks = true)
        if (card.explain.isNotBlank()) Text(card.explain, style = MaterialTheme.typography.bodyMedium)
        if (!r.firstCorrect) {
            Text("错在哪？点一个（自己的判断，不算分）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Round.CAUSES.forEach { c -> FilterChip(selected = r.cause == c, onClick = { a.cause(c) }, label = { Text(c) }) }
            }
        }
    }
}

/** 选项列表。marks 时标出正确项、你选的、每项的错因 */
@Composable
private fun Options(card: Card, r: Round, selected: Int?, marks: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.options.forEachIndexed { i, o ->
            OptionRow(
                i, o,
                selected = selected == i,
                enabled = false,
                tag = if (marks) card.flawOf(i).takeIf { i != card.answer && card.hasFlaws } else null,
                state = if (!marks) OptionState.NORMAL else when {
                    i == card.answer -> OptionState.RIGHT
                    i == selected || i == r.bet -> OptionState.WRONG
                    else -> OptionState.NORMAL
                },
            )
        }
    }
}

private enum class OptionState { NORMAL, RIGHT, WRONG }

@Composable
private fun OptionRow(
    index: Int,
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    tag: String? = null,
    state: OptionState = OptionState.NORMAL,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val border = when {
        state == OptionState.RIGHT -> scheme.tertiary
        state == OptionState.WRONG -> scheme.error
        selected -> scheme.primary
        else -> scheme.outlineVariant
    }
    val color by animateColorAsState(border, label = "border")
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected || state != OptionState.NORMAL) 2.dp else 1.dp, color),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CircleShape, color = if (selected) scheme.primary else scheme.surfaceVariant, modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(Card.letter(index), color = if (selected) scheme.onPrimary else scheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            tag?.let {
                Spacer(Modifier.width(6.dp))
                AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
}
