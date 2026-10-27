package com.yishou.app.gate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yishou.app.ui.*

@Composable
fun ProbeGateView(state: GateState, vm: GateViewModel) {
    val flow = vm.probe
    val review = vm.review
    if (review != null) {
        if (!review.pullBack) DiffCardView(review, reveal = review.verdict.passed || flow?.exhausted == true || vm.card.value.done)
        else Text(review.verdict.note)
        if (!review.verdict.passed) Button(onClick = vm::retry, enabled = !state.busy) { Text("接着来") }
        return
    }
    if (flow == null) {
        Text("这次没能备好题")
        Button(onClick = vm::retry, enabled = !state.busy) { Text("重试") }
        return
    }
    if (flow.canSelect()) {
        Text(flow.question.prompt)
        flow.question.options.forEachIndexed { i, option ->
            Button(onClick = { vm.selectProbeOption(i) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${'A' + i}．$option") }
        }
    } else {
        vm.feed.forEach { Text("先找：$it") }
        QuestionInput(flow, state.busy, state.revision, vm::changed, vm::submitProbe,
            onShow = { vm.showProbe(flow) }, onEdit = vm::editProbe, onVoiceStart = vm::voiceProbeStart)
    }
}
