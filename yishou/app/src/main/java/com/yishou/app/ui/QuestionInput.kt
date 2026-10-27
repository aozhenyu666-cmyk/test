package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yishou.app.gate.GateFlow
import kotlinx.coroutines.delay

/** 线程与入口使用同一个输入框和计时，语音回填单独记。 */
@Composable
fun QuestionInput(flow: GateFlow, busy: Boolean, revision: Int, onChanged: () -> Unit, onSubmit: () -> Unit,
    onShow: () -> Unit = { flow.shown() }, onEdit: (String, Boolean) -> Unit = { text, voice -> flow.edit(text, voice); onChanged() },
    onVoiceStart: () -> Unit = { flow.voiceStart() }) {
    LaunchedEffect(flow) { onShow() }
    Text(flow.question.prompt, style = MaterialTheme.typography.bodyLarge)
    var left by remember(flow) { mutableLongStateOf(flow.remainingFreeze) }
    LaunchedEffect(flow, revision) {
        do { left = flow.remainingFreeze; if (left > 0) delay(minOf(left, 250L)) } while (left > 0)
    }
    val field = remember(flow) { ProbeTextFieldState(flow.recorder) }
    ProbeTextField(flow.answer, { onEdit(it, false) }, field, modifier = Modifier.fillMaxWidth(),
        enabled = !busy && !flow.exhausted && !flow.canSelect() && left == 0L,
        onVoiceStart = onVoiceStart, onVoiceText = { onEdit(it, true) })
    if (left > 0) Text("先想一会儿，还需 ${(left + 999) / 1000} 秒")
    if (flow.fragment) Text("把刚才卡住的半截念头原样说出来")
    flow.verdict?.takeIf { !it.passed }?.let {
        Text(it.note)
        flow.missedHints().forEach { hint -> Text("提示：$hint") }
        flow.trace?.let { trace -> DiffCardView(TurnReview(flow.question, flow.answer, trace, it), reveal = flow.exhausted) }
    }
    if (!flow.canSelect() && !flow.exhausted) Button(onClick = onSubmit, enabled = !busy && left == 0L && flow.answer.isNotBlank()) {
        Text(if (busy) "正在核对" else "交这一手（${flow.attempts}/3）")
    }
}
