package com.yishou.app.gate

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yishou.app.reflex.GatePlan
import com.yishou.app.ui.VoiceTextField

/** 题库里的选择题：题目和选项一起给，点了就揭晓答案和解析，答对够数放行。 */
@Composable
fun ChoiceGateView(state: GateState, vm: GateViewModel) {
    val d = vm.choice
    if (d == null) {
        Text("这次没能备好题")
        Button(onClick = vm::retry, enabled = !state.busy) { Text("重试") }
        return
    }
    val card = d.card
    Text("题库里的题：答对 ${GateChoices.NEED} 道放行 ${GatePlan.FULL_PASS_MIN} 分钟（已对 ${vm.choiceRight} 道）", style = MaterialTheme.typography.labelLarge)
    if (card.sentences.isNotEmpty()) Text(card.sentences.joinToString(""))
    Text(card.question, style = MaterialTheme.typography.titleSmall)
    val picked = vm.chosen
    if (picked == null) {
        card.options.forEachIndexed { i, option ->
            Button(onClick = { vm.selectChoice(i) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${'A' + i}．$option") }
        }
        return
    }
    card.options.forEachIndexed { i, option ->
        val mark = when (i) { card.answer -> "✓ "; picked -> "✗ "; else -> "" }
        val color = when (i) { card.answer -> MaterialTheme.colorScheme.primary; picked -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
        Text("$mark${'A' + i}．$option", color = color)
    }
    val right = picked == card.answer
    Text(if (right) "对了" else "没对，正确答案是 ${'A' + card.answer}", color = if (right) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    if (card.explain.isNotBlank()) Text("解析：${card.explain}", style = MaterialTheme.typography.bodySmall)
    if (vm.choiceRight >= GateChoices.NEED) {
        if (state.isTest) {
            Text("够数了；测试页不放行")
            OutlinedButton(onClick = vm::retry, enabled = !state.busy) { Text("再来一道") }
        } else {
            VoiceTextField(state.exit, vm::onExitChange, "看完做什么（可不填）", Modifier.fillMaxWidth())
            Button(onClick = vm::passChoice, enabled = !state.busy) { Text("通过，放行 ${GatePlan.FULL_PASS_MIN} 分钟") }
        }
    } else {
        Button(onClick = vm::retry, enabled = !state.busy) { Text("下一道") }
    }
}
