package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.yishou.app.judge.*
import com.yishou.app.reflex.*

/** 未过时右栏只留命中标记，判据和参考不能提前变成下一轮的答案。 */
@Composable
fun DiffCardView(review: TurnReview, reveal: Boolean = review.verdict.passed) {
    val diff = remember(review) { DiffCards.build(review.q, review.answer, review.trace, review.verdict) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("你刚才的路", style = MaterialTheme.typography.titleMedium)
            diff.yours.forEach { Text(it) }
        }
        Column(Modifier.weight(1f)) {
            Text(if (reveal) "最短路径" else "要点核对", style = MaterialTheme.typography.titleMedium)
            if (reveal) diff.shortest.forEach { Text(it) }
            else review.q.points.forEachIndexed { i, p -> Text("${if (review.verdict.hits[p.id] != null) "✓" else "✗"} 要点 ${i + 1}") }
        }
    }
    if (reveal && diff.reference.isNotBlank()) Text("参考：${diff.reference}")
}

@Composable
fun TriggerEditor(question: LockedQ, busy: Boolean, onSave: (String, String) -> Unit) {
    var signal by remember(question.id) { mutableStateOf("") }
    var action by remember(question.id) { mutableStateOf("") }
    var selection by remember(question.id) { mutableStateOf(TextFieldValue(question.prompt, TextRange.Zero)) }
    val problem = if (signal.isBlank() && action.isBlank()) null else Reflexes.check(signal, action, question.prompt)
    Text("下次看到【信号】，我先【动作】。")
    OutlinedTextField(selection, { selection = it.copy(text = question.prompt) }, readOnly = true,
        label = { Text("长按选题面里的字") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = {
        val range = selection.selection
        signal = question.prompt.substring(range.min, range.max)
    }, enabled = !busy && !selection.selection.collapsed) { Text("把选中的字填作信号") }
    OutlinedTextField(signal, { signal = it }, label = { Text("信号（题面原文）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    OutlinedTextField(action, { action = it }, label = { Text("我先做的具体动作") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { onSave(signal, action) }, enabled = !busy && signal.isNotBlank() && action.isNotBlank() && problem == null) { Text("存下这一句") }
}
