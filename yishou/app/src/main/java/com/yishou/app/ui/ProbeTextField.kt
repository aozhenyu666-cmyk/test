package com.yishou.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.TextFieldValue
import com.yishou.app.judge.ProbeRecorder
import com.yishou.app.judge.ProbeTrace

/** 事件由会话记录，状态对象供提交时取得完整轨迹。 */
class ProbeTextFieldState(val recorder: ProbeRecorder) {
    fun trace(): ProbeTrace = recorder.submit()
}

@Composable
fun ProbeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    state: ProbeTextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onVoiceStart: () -> Unit = {},
    onVoiceText: (String) -> Unit = onValueChange,
) {
    var field by remember(state) { mutableStateOf(TextFieldValue(value)) }
    var note by remember(state) { mutableStateOf<String?>(null) }
    if (field.text != value) field = TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = field, onValueChange = { next ->
                field = next
                if (next.text != value) onValueChange(next.text)
            }, label = { Text("先说要点") }, enabled = enabled, minLines = 2, modifier = Modifier.weight(1f))
            MicButton(onText = { t -> onVoiceText(value.trimEnd().let { if (it.isEmpty()) t else "$it，$t" }) },
                enabled = enabled, onNote = { note = it }, onStart = onVoiceStart)
        }
        VoiceNote(note)
    }
}
