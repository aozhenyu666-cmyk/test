package com.behaviordept.app.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.NumberField
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper

@Composable
private fun PaperDialog(
    title: String,
    confirm: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = Paper.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.page,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = p.ink) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirm, color = if (confirmEnabled) p.red else p.ink2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.ink) } },
    )
}

/** 改标准：一行一条。 */
@Composable
fun StandardDialog(sub: SubSkill, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(sub.standard) }
    PaperDialog("「${sub.name}」的标准", "保存", onConfirm = { onSave(text) }, onDismiss = onDismiss) {
        Hint("一行一条。写“做了什么”，能被看出来；不写“感觉怎么样”。")
        RuledTextField(text, { text = it }, minLines = 6)
    }
}

/** 自己写一个练习。 */
@Composable
fun DrillDialog(sub: SubSkill, onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("15") }
    var metric by remember { mutableStateOf("") }
    PaperDialog(
        "给「${sub.name}」加一个练习",
        "保存",
        confirmEnabled = title.isNotBlank() && method.isNotBlank(),
        onConfirm = { onSave(title, method, minutes.toIntOrNull() ?: 15, metric) },
        onDismiss = onDismiss,
    ) {
        SectionLabel("练习名")
        RuledTextField(title, { title = it }, singleLine = true, placeholder = "例如：预瞄拐角")
        SectionLabel("做法")
        RuledTextField(method, { method = it }, minLines = 3, placeholder = "怎么做、做多少次")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("时长（分钟）")
            NumberField(minutes, { minutes = it })
        }
        SectionLabel("练完记什么数字")
        RuledTextField(metric, { metric = it }, singleLine = true, placeholder = "例如：首发命中率%；越低越好的写“（越低越好）”")
    }
}

/** 让 AI 重写标准前，先说说目标和现状。 */
@Composable
fun GoalDialog(onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var goal by remember { mutableStateOf("") }
    PaperDialog("让 AI 重写标准", "开始拆解", onConfirm = { onGo(goal) }, onDismiss = onDismiss) {
        Hint("会用旗舰档模型，沿用现有子能力名，只重写每项的标准。说说你的目标和现在卡在哪，越具体越好（可不填）。")
        RuledTextField(goal, { goal = it }, minLines = 3, placeholder = "例如：想稳定撤离；经常在楼梯口被架死")
    }
}

/** AI 拆出来的标准：确认后才替换。 */
@Composable
fun StandardsPreviewDialog(list: List<Pair<String, List<String>>>, onApply: () -> Unit, onDiscard: () -> Unit) {
    val p = Paper.colors
    PaperDialog("AI 拆出的标准", "用这一版", onConfirm = onApply, onDismiss = onDiscard) {
        list.forEach { (name, lines) ->
            Text(name, style = MaterialTheme.typography.titleMedium, color = p.red)
            lines.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = p.ink) }
        }
        Hint("替换后还能逐条手改。")
    }
}

/** 动作技能的实战复测：逐条对照标准，记下达标几条。 */
@Composable
fun MotorRetestDialog(sub: SubSkill, onDismiss: () -> Unit, onSave: (Int, Int, String) -> Unit) {
    val lines = sub.standard.lines().filter { it.isNotBlank() }
    val met = remember { mutableStateListOf<Boolean>().apply { repeat(lines.size) { add(false) } } }
    var note by remember { mutableStateOf("") }
    PaperDialog(
        "实战复测：${sub.name}",
        "记录",
        confirmEnabled = lines.isNotEmpty(),
        onConfirm = { onSave(met.count { it }, lines.size, note) },
        onDismiss = onDismiss,
    ) {
        Hint("看实战录像（或 Gemini 的分析），这一段里做到了的标准点一下。")
        lines.forEachIndexed { i, line ->
            Row(
                Modifier.clickable { met[i] = !met[i] }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TianZiGeCell(done = met[i], size = 30.dp)
                Spacer(Modifier.width(12.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Paper.colors.ink)
            }
        }
        SectionLabel("备注（可粘贴 Gemini 的分析）")
        RuledTextField(note, { note = it }, minLines = 3)
    }
}
