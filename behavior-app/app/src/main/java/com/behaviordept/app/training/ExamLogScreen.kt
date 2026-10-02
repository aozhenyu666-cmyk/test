package com.behaviordept.app.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.ExamKind
import com.behaviordept.app.data.ExamSection
import com.behaviordept.app.data.LostReason
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.Template
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.ChoiceChip
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.NumberField
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import kotlinx.coroutines.launch

/** 考试登记里一个模块的一行。 */
class ModuleRow(val module: String, total: String) {
    var correct by mutableStateOf("")
    var total by mutableStateOf(total)
    var minutes by mutableStateOf("")
    var reason by mutableStateOf<String?>(null)
}

class ExamLogViewModel(private val c: AppContainer, skillIdArg: Long) : ViewModel() {
    var skill by mutableStateOf<Skill?>(null)
        private set
    val rows = mutableStateListOf<ModuleRow>()
    var kind by mutableStateOf(ExamKind.MOCK)
    var score by mutableStateOf("")
    var essay by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var hasEssay by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val s = (if (skillIdArg > 0) c.training.skill(skillIdArg) else c.training.skills().firstOrNull { it.template == Template.EXAM })
                ?: return@launch
            skill = s
            val last = c.training.lastTotals(s.id)
            val subs = c.training.subSkills(s.id)
            hasEssay = subs.any { it.name == ESSAY }
            subs.filter { it.name != ESSAY }.forEach { sub ->
                val t = last[sub.name] ?: Seeds.EXAM_DEFAULT_TOTALS[sub.name]
                rows.add(ModuleRow(sub.name, t?.toString().orEmpty()))
            }
        }
    }

    fun save(done: () -> Unit) {
        val s = skill ?: return
        val sections = mutableListOf<ExamSection>()
        for (r in rows) {
            if (r.correct.isBlank()) continue
            val correct = r.correct.toIntOrNull() ?: continue
            val total = r.total.toIntOrNull() ?: 0
            if (total <= 0 || correct > total) {
                error = "「${r.module}」做对 $correct 题，但总题数是 ${r.total.ifBlank { "空" }}"
                return
            }
            sections += ExamSection(sittingId = 0, module = r.module, correct = correct, total = total, minutes = r.minutes.toIntOrNull(), lostReason = r.reason)
        }
        val essayScore = essay.toDoubleOrNull()
        if (sections.isEmpty() && essayScore == null) {
            error = "至少填一个模块做对了几题"
            return
        }
        error = null
        viewModelScope.launch {
            c.training.logExam(s.id, kind, score.toDoubleOrNull(), essayScore, sections, "")
            done()
        }
    }
}

/** 登记一次模考 / 真题 / 真考：每个模块填做对几题，2 分钟内填完。 */
@Composable
fun ExamLogScreen(skillId: Long, onBack: () -> Unit) {
    val vm = appViewModel { ExamLogViewModel(it, skillId) }
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
        val s = vm.skill
        if (s == null) {
            Hint("还没有考试类的技能。")
            return@Column
        }
        Column {
            SectionLabel(s.name)
            Text("登记一次考试", style = MaterialTheme.typography.headlineLarge, color = p.ink)
            Hint("模考、真题、真考都算实战。只填做对几题，题量会记住上次填的。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ExamKind.MOCK, ExamKind.PAST, ExamKind.REAL).forEach { k ->
                ChoiceChip(ExamKind.label(k), selected = vm.kind == k, modifier = Modifier.weight(1f)) { vm.kind = k }
            }
        }
        vm.rows.forEach { row -> ModuleCard(row) }
        PaperCard {
            SectionLabel("分数（可不填）")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("行测", style = MaterialTheme.typography.titleSmall, color = p.ink)
                NumberField(vm.score, { vm.score = it }, width = 84.dp, decimal = true, placeholder = "分")
                if (vm.hasEssay) {
                    Spacer(Modifier.padding(start = 6.dp))
                    Text("申论", style = MaterialTheme.typography.titleSmall, color = p.ink)
                    NumberField(vm.essay, { vm.essay = it }, width = 84.dp, decimal = true, placeholder = "分")
                }
            }
        }
        vm.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.red) }
        InkButton("登记", onClick = { vm.save(onBack) })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModuleCard(row: ModuleRow) {
    val p = Paper.colors
    PaperCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.module, style = MaterialTheme.typography.titleMedium, color = p.ink, modifier = Modifier.weight(1f))
            NumberField(row.correct, { row.correct = it }, width = 58.dp, placeholder = "对")
            Text("/", style = MaterialTheme.typography.titleMedium, color = p.ink2)
            NumberField(row.total, { row.total = it }, width = 58.dp, placeholder = "共")
            NumberField(row.minutes, { row.minutes = it }, width = 58.dp, placeholder = "分钟")
        }
        val c = row.correct.toIntOrNull()
        val t = row.total.toIntOrNull()
        if (c != null && t != null && c < t) {
            Spacer(Modifier.height(10.dp))
            Hint("主要丢分原因")
            Spacer(Modifier.height(6.dp))
            LostReason.ALL.chunked(2).forEach { pair ->
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { r ->
                        ChoiceChip(r, selected = row.reason == r, modifier = Modifier.weight(1f), height = 38.dp, red = true) {
                            row.reason = if (row.reason == r) null else r
                        }
                    }
                }
            }
        }
    }
}
