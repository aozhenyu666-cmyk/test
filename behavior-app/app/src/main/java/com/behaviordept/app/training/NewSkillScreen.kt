package com.behaviordept.app.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.ai.AiException
import com.behaviordept.app.data.Template
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.ChoiceChip
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import kotlinx.coroutines.launch

class NewSkillViewModel(private val c: AppContainer) : ViewModel() {
    var name by mutableStateOf("")
    var template by mutableStateOf(Template.MOTOR)
    var goal by mutableStateOf("")
    /** 手写的拆解：每段“子能力名”一行，下面每行一条标准，段之间空一行。 */
    var manual by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var proposal by mutableStateOf<List<Pair<String, List<String>>>>(emptyList())
        private set

    fun decompose() {
        if (name.isBlank() || busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                proposal = c.coach.decomposeSkill(name, Template.label(template), emptyList(), goal)
                manual = proposal.joinToString("\n\n") { (n, lines) -> n + "\n" + lines.joinToString("\n") }
            } catch (e: AiException) {
                error = e.message
            } catch (e: Exception) {
                error = "出错了：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** 把文本拆成 (子能力, 标准)：空行分段，每段第一行是名字。 */
    fun parsed(): List<Pair<String, List<String>>> =
        manual.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.lines().map { it.trim().removePrefix("-").removePrefix("·").trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) null else lines.first() to lines.drop(1)
        }

    fun save(done: (Long) -> Unit) {
        val cats = parsed()
        if (name.isBlank()) {
            error = "先写技能名"
            return
        }
        if (cats.isEmpty()) {
            error = "至少写一个子能力"
            return
        }
        viewModelScope.launch {
            val id = c.training.create(SeedSkill(name.trim(), template, cats.map { (n, s) -> SeedCategory(n, s, emptyList()) }))
            done(id)
        }
    }
}

/** 新建技能：写名字 → 让 AI 拆子能力和标准（或自己写）→ 保存后去选重点、加练习。 */
@Composable
fun NewSkillScreen(onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val vm = appViewModel { NewSkillViewModel(it) }
    val p = Paper.colors
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuietButton("‹ 返回", onBack)
        Text("新建技能", style = MaterialTheme.typography.headlineLarge, color = p.ink)
        SectionLabel("技能名")
        RuledTextField(vm.name, { vm.name = it }, singleLine = true, placeholder = "例如：三步上篮、羽毛球正手高远球")
        SectionLabel("类型")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Template.MOTOR, Template.COMPETITIVE, Template.EXAM).forEach { t ->
                ChoiceChip(Template.label(t), selected = vm.template == t, modifier = Modifier.weight(1f)) { vm.template = t }
            }
        }
        Hint(
            when (vm.template) {
                Template.MOTOR -> "动作技能：按标准动作拆成几段，每段写能看出来的要点。练的时候可以让 Gemini 实时看，把它的分析贴进记录。"
                Template.COMPETITIVE -> "对抗竞技：子能力就是“死因大类”，每局登记死在哪一类，攒够 20 次自动诊断。"
                else -> "考试：子能力就是考试模块，登记模考时按模块填做对几题。"
            },
        )
        PaperCard {
            SectionLabel("让 AI 拆解（旗舰档）")
            Spacer(Modifier.height(8.dp))
            RuledTextField(vm.goal, { vm.goal = it }, minLines = 2, placeholder = "目标和现状（可不填）：例如上篮总是走步、出手点太低")
            Spacer(Modifier.height(10.dp))
            LineButton(if (vm.busy) "拆解中…" else "让 AI 拆出子能力和标准", onClick = { vm.decompose() }, enabled = !vm.busy && vm.name.isNotBlank(), modifier = Modifier.fillMaxWidth())
        }
        SectionLabel("子能力和标准")
        Hint("每段第一行写子能力名，下面每行一条标准，段之间空一行。AI 拆完会填在这里，可以直接改。")
        RuledTextField(vm.manual, { vm.manual = it }, minLines = 10, placeholder = "步伐\n最后两步一大一小\n起跳脚是内侧脚\n\n出手\n在最高点出手\n用指尖拨球")
        vm.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.red) }
        InkButton("保存", onClick = { vm.save(onCreated) }, enabled = vm.name.isNotBlank() && vm.manual.isNotBlank())
        Spacer(Modifier.height(24.dp))
    }
}
