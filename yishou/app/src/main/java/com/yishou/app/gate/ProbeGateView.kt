package com.yishou.app.gate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.ui.*

/**
 * 入口这一题：先说两个槽 → 亮选项 → 选。
 * 两次没中就亮选项，同时把标准精简逻辑摆出来：不让人卡死在“说不出来”上。
 */
@Composable
fun ProbeGateView(state: GateState, vm: GateViewModel) {
    val flow = vm.probe
    val review = vm.review
    val card by vm.card.collectAsStateWithLifecycle()
    if (review != null) {
        if (!review.pullBack) DiffCardView(review, reveal = review.verdict.passed || flow?.revealed == true || card.done)
        else Text(review.verdict.note)
        // 没过的要有出路：线程是接着想，题库是换一道。原来这里没有出口，等于卡死。
        if (!review.verdict.passed) Button(onClick = vm::retry, enabled = !state.busy) { Text("换一道") }
        return
    }
    if (flow == null) {
        Text("这次没能备好题")
        Button(onClick = vm::retry, enabled = !state.busy) { Text("重试") }
        return
    }
    if (flow.canSelect()) {
        Text(flow.question.prompt)
        if (flow.revealed) {
            Text("两次都没说到点子上，这次直接看：", style = MaterialTheme.typography.titleSmall)
            // 标准精简逻辑：要点原文就摆在这里，选项照样自己做
            flow.question.points.forEachIndexed { i, p -> Text("要点 ${i + 1}：${p.criterion}") }
        }
        flow.question.options.forEachIndexed { i, option ->
            Button(onClick = { vm.selectProbeOption(i) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${'A' + i}．$option") }
        }
    } else {
        vm.feed.forEach { Text("先找：$it") }
        QuestionInput(flow, state.busy, state.revision, vm::changed, vm::submitProbe,
            onShow = { vm.showProbe(flow) }, onEdit = vm::editProbe, onEditVoice = vm::editProbeVoice, onVoiceStart = vm::voiceProbeStart)
    }
}
