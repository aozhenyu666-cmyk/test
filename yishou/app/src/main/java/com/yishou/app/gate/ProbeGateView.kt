package com.yishou.app.gate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yishou.app.card.SessionState
import com.yishou.app.ui.ProbeTextField
import com.yishou.app.ui.ProbeTextFieldState
import kotlinx.coroutines.delay

@Composable
fun ProbeGateView(state: GateState, card: SessionState, vm: GateViewModel) {
    val flow = vm.probe ?: return
    LaunchedEffect(flow) { vm.showProbe(flow) }
    Text(flow.question.prompt, style = MaterialTheme.typography.bodyLarge)
    if (card.done) {
        val correct = card.round?.finalCorrect == true
        Text(if (correct) "选项核对正确" else "选项不对，请换一题")
        Text("参考：${flow.question.reference}")
        return
    }
    if (flow.canSelect()) {
        Text(flow.verdict?.note ?: "要点过了，选一个")
        flow.question.options.forEachIndexed { index, option ->
            Button(onClick = { vm.selectProbeOption(index) }, modifier = Modifier.fillMaxWidth()) { Text("${'A' + index}．$option") }
        }
        return
    }
    var freezeMs by remember(flow) { mutableStateOf(flow.remainingFreeze) }
    LaunchedEffect(flow, state.probeRevision) {
        do {
            freezeMs = flow.remainingFreeze
            if (freezeMs > 0) delay(minOf(freezeMs, 250L))
        } while (freezeMs > 0)
    }
    val field = remember(flow) { ProbeTextFieldState(flow.recorder) }
    ProbeTextField(flow.answer, { vm.editProbe(it) }, field, modifier = Modifier.fillMaxWidth(),
        enabled = !state.probeBusy && !flow.exhausted && freezeMs == 0L,
        onVoiceStart = vm::voiceProbeStart, onVoiceText = { vm.editProbe(it, voice = true) })
    if (freezeMs > 0) Text("答得太快了，先想一想，还需 ${(freezeMs + 999) / 1000} 秒。")
    if (flow.fragment) Text("把卡住时那个半截念头原样说出来。")
    state.probeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    // 只显示判卷的一句话和提示，不显示判据原文：判据一露，下一次照抄就能过
    flow.verdict?.takeIf { !it.passed }?.let { Text(it.note, color = MaterialTheme.colorScheme.error) }
    flow.missedHints().forEach { Text("提示：$it") }
    if (flow.exhausted) {
        Text("这题已尝试三次，请换一题。")
        Button(onClick = vm::retry) { Text("换一题") }
        if (state.wrongInRow >= 2) OutlinedButton(onClick = vm::startWait) { Text("等 10 分钟，换 5 分钟") }
    } else if (flow.unavailable) {
        Text("判卷暂时不在（没网或接口出错），这次不算。")
        Button(onClick = vm::submitProbe, enabled = !state.probeBusy) { Text("再试一次") }
        OutlinedButton(onClick = vm::startWait) { Text("等 10 分钟，换 5 分钟") }
    } else Button(onClick = vm::submitProbe, enabled = !state.probeBusy && freezeMs == 0L && flow.answer.isNotBlank()) {
        Text(if (state.probeBusy) "核对中" else "提交要点（${flow.attempts}/3）")
    }
}
