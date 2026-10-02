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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.MatchLog
import com.behaviordept.app.data.MatchResult
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.Template
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.ChoiceChip
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.PenMark
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MatchLogState(val skill: Skill, val subs: List<SubSkill>, val matches: List<MatchLog>)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchLogViewModel(private val c: AppContainer, skillIdArg: Long) : ViewModel() {
    private val skillId = MutableStateFlow<Long?>(if (skillIdArg > 0) skillIdArg else null)

    init {
        if (skillIdArg <= 0) {
            // 从桌面快捷方式进来时没有 id：用第一个对抗竞技技能。
            viewModelScope.launch {
                skillId.value = c.training.skills().firstOrNull { it.template == Template.COMPETITIVE }?.id ?: -1L
            }
        }
    }

    val state: StateFlow<MatchLogState?> = skillId.filterNotNull().flatMapLatest { id ->
        if (id <= 0) flowOf<MatchLogState?>(null) else combine(
            c.training.observeSkill(id),
            c.training.observeSubSkills(id),
            c.training.observeMatches(id),
        ) { skill, subs, matches -> skill?.let { MatchLogState(it, subs, matches) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var result by mutableStateOf<String?>(null)
    var cause by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    var saved by mutableStateOf(0)
        private set

    val canSave: Boolean get() = result == MatchResult.EXTRACTED || (result == MatchResult.DIED && cause != null)

    fun save() {
        val s = state.value ?: return
        val r = result ?: return
        viewModelScope.launch {
            c.training.logMatch(s.skill.id, r, if (r == MatchResult.DIED) cause else null, note)
            result = null
            cause = null
            note = ""
            saved++
        }
    }
}

/** 登记一局：每局结束 30 秒内点完（PRD M3 验收）。 */
@Composable
fun MatchLogScreen(skillId: Long, onBack: () -> Unit, onOpenSkill: (Long) -> Unit) {
    val vm = appViewModel { MatchLogViewModel(it, skillId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val p = Paper.colors
    val s = state

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        QuietButton("‹ 返回", onBack)
        if (s == null) {
            Hint("还没有对抗竞技类的技能。去“训练”里新建一个。")
            return@Column
        }
        val today = Time.today()
        val todayMatches = s.matches.filter { Time.dateOf(it.time) == today }
        val deaths = Diagnosis.deathCount(s.matches)
        Column {
            SectionLabel(s.skill.name)
            Text("登记一局", style = MaterialTheme.typography.headlineLarge, color = p.ink)
            Text(
                "今天第 ${todayMatches.size + 1} 局 · 累计阵亡 $deaths 次" +
                    if (deaths < Diagnosis.MIN_DEATHS) "（满 ${Diagnosis.MIN_DEATHS} 次出诊断）" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = p.ink2,
            )
        }

        if (vm.saved > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PenMark("✓", 28.dp, p.red)
                Text("  已登记。下一局结束再来。", style = MaterialTheme.typography.titleSmall, color = p.red)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChip("撤离", selected = vm.result == MatchResult.EXTRACTED, modifier = Modifier.weight(1f), height = 72.dp) {
                vm.result = MatchResult.EXTRACTED
            }
            ChoiceChip("阵亡", selected = vm.result == MatchResult.DIED, modifier = Modifier.weight(1f), height = 72.dp, red = true) {
                vm.result = MatchResult.DIED
            }
        }

        if (vm.result == MatchResult.DIED) {
            SectionLabel("死在哪类问题上")
            s.subs.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { sub ->
                        ChoiceChip(sub.name, selected = vm.cause == sub.name, modifier = Modifier.weight(1f), height = 56.dp, red = true) {
                            vm.cause = sub.name
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val chosen = s.subs.firstOrNull { it.name == vm.cause }
            chosen?.standard?.lines()?.firstOrNull()?.let { Hint("对照标准：$it") }
            RuledTextField(vm.note, { vm.note = it }, singleLine = true, placeholder = "一句话（可不填）：比如楼梯口被架")
        }

        InkButton("登记", onClick = { vm.save() }, enabled = vm.canSave)
        QuietButton("看死因诊断 ›", onClick = { onOpenSkill(s.skill.id) })
        Spacer(Modifier.height(24.dp))
    }
}
