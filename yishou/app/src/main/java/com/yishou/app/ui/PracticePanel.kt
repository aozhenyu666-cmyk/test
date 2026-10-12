package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.training.PracticeMode
import com.yishou.app.training.PracticeSession

/** 可用于任何材料的一手，没有固定选项或标准答案。 */
@Composable
fun PracticePanel(session: PracticeSession, onEdit: ((Long?) -> Unit)? = null, showHistory: Boolean = true) {
    val s by session.state.collectAsStateWithLifecycle()
    val attachment by session.attachment.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    val task = s.task
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (task == null) {
            Text(if (s.mode == PracticeMode.ACTION) "先接住眼前的事" else "从一个真实问题开始", style = MaterialTheme.typography.headlineSmall)
            Text(if (s.mode == PracticeMode.ACTION) "说说要做什么、已有的情况。买菜截图、待办说明和工作材料都可以。" else "带来一个观点、文章、题目或困惑；也可以先练一个完整的小情境。")
            if (onEdit != null) Button(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) { Text("带入我的事情或材料") }
            if (s.mode == PracticeMode.THINK) TextButton(onClick = session::startScenario) { Text("没有材料，给我一个情境") }
            return@Column
        }
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Text(task.goal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.breakpoint?.known?.takeIf { it.isNotBlank() }?.let { Text("已经得到：$it", style = MaterialTheme.typography.bodySmall) }
        if (onEdit != null) TextButton(onClick = { onEdit(task.id) }, enabled = !s.busy) { Text("编辑目标与材料") }
        if (s.paused) {
            Text("已提交的每一手和当前断点都留着。想继续时，从这里接上。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = session::start) { Text("接着走一步") }
            return@Column
        }
        if (showHistory && s.history.isNotEmpty()) {
            TextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "收起之前的几手" else "看看之前的几手（${s.history.size}）") }
            if (historyOpen) s.history.forEach { round ->
                CoachBubble(round.coachMove)
                UserBubble(round.userAnswer)
                VerdictLine(round)
            }
        }
        if (!historyOpen) s.last?.let { round -> UserBubble(round.userAnswer); VerdictLine(round) }
        if (s.busy) MatrixLoader(if (s.move == null) "陪练在接你的局面" else "陪练在检查这一步")
        s.move?.let { CoachBubble(it) }
        if (s.move == null && !s.busy) {
            Button(onClick = session::start, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (s.mode == PracticeMode.ACTION) "确定下一步" else "开始思维训练") }
        }
        AnswerComposer(
            answer = s.answer,
            starters = s.starters,
            onAnswerChange = session::answer,
            attachment = attachment,
            onAttach = session::attach,
            onClearAttachment = session::clearAttachment,
            submitting = s.busy,
            onSubmit = session::submit,
            error = s.error,
            hint = if (s.move == null) "材料准备好后，点“作为材料接上”或开始陪练。" else null,
            placeholder = if (s.mode == PracticeMode.ACTION) "眼前情况、做了什么、结果怎样" else "我的判断、依据，或还没想明白的地方",
            allowSubmit = s.move != null && (s.answer.isNotBlank() || attachment is com.yishou.app.system.Attachment.Ready),
            onEditAttachment = session.attachment::edit,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = session::addMaterial, enabled = !s.busy) { Text("作为材料接上") }
            if (s.move != null) TextButton(onClick = session::demo, enabled = !s.busy) { Text("卡住了，示范一步") }
        }
        if (s.mode == PracticeMode.THINK && s.move != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiceRoller(enabled = !s.busy, shake = false, onRolled = session::roll)
                Text("换一种思考动作", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.move != null) TextButton(onClick = session::stop) { Text("先收起，留住断点") }
    }
}
