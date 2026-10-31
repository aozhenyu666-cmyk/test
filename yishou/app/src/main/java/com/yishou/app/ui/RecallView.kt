package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.yishou.app.reflex.Reflex

@Composable
fun RecallView(reflex: Reflex, source: String, busy: Boolean, feedback: String?, onSubmit: (String) -> Unit, onNext: () -> Unit, echo: Boolean = false) {
    var answer by remember(reflex.id) { mutableStateOf("") }
    Text(if (echo) "先回想一句，再进正题" else "低能量路：默写一句，换 5 分钟", style = MaterialTheme.typography.titleMedium)
    Text("看到【${reflex.signal}】时，我先……")
    if (source.isNotBlank()) Text("那次的题：${source.take(60)}")
    if (feedback == null) {
        VoiceTextField(answer, { answer = it }, "写出原来那个动作", Modifier.fillMaxWidth())
        Button(onClick = { onSubmit(answer) }, enabled = !busy && answer.isNotBlank()) { Text("默写好了") }
    } else {
        Text(feedback)
        Button(onClick = onNext, enabled = !busy) { Text(if (echo) "进入正题" else "继续") }
    }
}
