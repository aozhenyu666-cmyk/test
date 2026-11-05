package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yishou.app.gate.GateFlow
import com.yishou.app.judge.Slots
import com.yishou.app.judge.TwoSlots
import kotlinx.coroutines.delay

/**
 * 线程与入口使用同一个输入：两个槽（主体 / 方向）比一个空白框好启动。
 *
 * 原来要求直接写出一整句主旨，想不出第一句就退出去了；交接记录里也写着
 * “切屏后无法复述、复杂关系留不住”。所以这里不跳页、不藏题面：
 * 抓到的词留在屏幕上，拼出来的句子当场显示，作为外部支架。
 * 想写整句也可以——两个槽拼起来就是一句话，判卷看的是拼好的那句。
 */
@Composable
fun QuestionInput(flow: GateFlow, busy: Boolean, revision: Int, onChanged: () -> Unit, onSubmit: () -> Unit,
    onShow: () -> Unit = { flow.shown() }, onEdit: (String) -> Unit = { text -> flow.slotEdit(text); onChanged() },
    onEditVoice: (String) -> Unit = { text -> flow.edit(text, voice = true); onChanged() },
    onVoiceStart: () -> Unit = { flow.voiceStart() }) {
    LaunchedEffect(flow) { onShow() }
    Text(flow.question.prompt, style = MaterialTheme.typography.bodyLarge)
    var left by remember(flow) { mutableLongStateOf(flow.remainingFreeze) }
    LaunchedEffect(flow, revision) {
        do { left = flow.remainingFreeze; if (left > 0) delay(minOf(left, 250L)) } while (left > 0)
    }
    val field = remember(flow) { ProbeTextFieldState(flow.recorder) }
    var subject by remember(flow) { mutableStateOf("") }
    var direction by remember(flow) { mutableStateOf("") }
    val slots = Slots(subject, direction)
    val enabled = !busy && !flow.canSelect() && left == 0L

    /** 两个槽一变就重拼这一句交给会话（判卷看的就是它） */
    fun sync() {
        val text = slots.sentence()
        if (text != flow.answer) onEdit(text)
        onChanged()
    }

    Text("先说要点：填两个词就行", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(subject, { subject = it; sync() },
            label = { Text("主体/话题") }, enabled = enabled, singleLine = true, modifier = Modifier.weight(1f))
        MicButton(onText = { t ->
            subject = t.trim()
            // 听写进来的整句单独记（探针会标成语音），不计为“自己打的字”
            val text = Slots(subject, direction).sentence()
            if (text != flow.answer) onEditVoice(text)
        }, enabled = enabled, onStart = onVoiceStart)
    }
    OutlinedTextField(direction, { direction = it; sync() },
        label = { Text("对策/方向") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (slots.sentence().isNotBlank()) Text("你这一句：${slots.sentence()}", style = MaterialTheme.typography.bodyMedium)

    if (left > 0) Text("先想一会儿，还需 ${(left + 999) / 1000} 秒")
    if (flow.fragment) Text("把刚才卡住的半截念头原样说出来")
    flow.verdict?.takeIf { !it.passed }?.let {
        Text(it.note)
        flow.missedHints().forEach { hint -> Text("提示：$hint") }
        // 没中时给一句追问，指出缺的那一层联系，而不是判错卡住
        if (!flow.canSelect()) Text(TwoSlots.nudge(slots, flow.missedHints()))
        flow.trace?.let { trace -> DiffCardView(TurnReview(flow.question, flow.answer, trace, it), reveal = flow.exhausted) }
    }
    if (!flow.canSelect()) Button(onClick = onSubmit,
        enabled = !busy && left == 0L && TwoSlots.canSubmit(slots),
        modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "正在核对" else "交这一手（${flow.attempts}/${flow.maxAttempts}）")
    }
}
