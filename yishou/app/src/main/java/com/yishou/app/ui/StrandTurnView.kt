package com.yishou.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.yishou.app.thread.MoveKind

@Composable
fun StrandTurnView(work: StrandWork, busy: Boolean, revision: Int, onChanged: () -> Unit, onSubmit: () -> Unit,
    onNext: () -> Unit, onSplit: () -> Unit, onTrigger: ((String, String) -> Unit)? = null) {
    val review = work.review
    if (review != null) {
        if (!review.pullBack) DiffCardView(review)
        else Text(review.verdict.note)
        if (review.verdict.passed) {
            if (onTrigger != null) TriggerEditor(review.q, busy, onTrigger)
            else Text("这一手过了，先存一句触发器")
        } else Button(onClick = onNext, enabled = !busy) { Text("接着想这一项") }
    } else work.flow?.let { flow ->
        QuestionInput(flow, busy, revision, onChanged, onSubmit)
    }
    if (work.strand?.pending?.kind == MoveKind.PULL_BACK || review?.pullBack == true) {
        OutlinedButton(onClick = onSplit, enabled = !busy) { Text("这是另一件事，另开线程") }
    }
    work.error?.let {
        Text(it)
        OutlinedButton(onClick = onNext, enabled = !busy) { Text("重试") }
    }
}
