package com.behaviordept.app.training

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.MatchLog
import com.behaviordept.app.data.Template
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep
import com.behaviordept.app.today.isReviewDue
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class LearningCard(val units: Int, val due: Int, val inProgress: Int)

data class TrainingOverview(val learning: LearningCard, val plans: List<SkillPlan>, val matches: List<MatchLog>)

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<TrainingOverview?> = combine(
        c.study.observeUnits(),
        c.training.observeSkills(),
        c.training.observeAllMatches(),
        c.training.observeDrillEvents(),
        c.training.observeAllSubSkills(),
    ) { units, _, matches, _, _ -> units to matches }
        .mapLatest { (units, matches) ->
            val now = Time.now()
            TrainingOverview(
                learning = LearningCard(
                    units = units.size,
                    due = units.count { it.isReviewDue(now) },
                    inProgress = units.count { it.currentStep() != UnitStep.DONE },
                ),
                plans = c.training.plans(now),
                matches = matches,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** 训练：所有技能共用一个引擎——标准 → 诊断 → 专项练 → 反馈 → 实战复测。 */
@Composable
fun TrainingScreen(
    onOpenStudy: () -> Unit,
    onOpenSkill: (Long) -> Unit,
    onNewSkill: () -> Unit,
) {
    val vm = appViewModel { TrainingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val p = Paper.colors

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader("训练", "标准 → 诊断 → 专项练 → 反馈 → 实战复测") }
        item {
            SkillCard(
                title = "学习",
                kind = "预习 · 讲一遍 · 间隔自测 · 举一反三",
                status = buildString {
                    append("${s.learning.units} 个单元")
                    if (s.learning.inProgress > 0) append(" · ${s.learning.inProgress} 个进行中")
                    if (s.learning.due > 0) append(" · ${s.learning.due} 个自测到期")
                },
                alert = s.learning.due > 0,
                onClick = onOpenStudy,
            )
        }
        items(s.plans, key = { it.skill.id }) { plan ->
            val matches = s.matches.filter { it.skillId == plan.skill.id }
            SkillCard(
                title = plan.skill.name,
                kind = plan.templateLabel,
                status = planStatus(plan, matches),
                alert = plan.retest !is RetestState.NotDue,
                focusDays = if (plan.focus != null) plan.drillDates.size else null,
                onClick = { onOpenSkill(plan.skill.id) },
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            LineButton("新建技能（例如羽毛球、三步上篮）", onClick = onNewSkill, modifier = Modifier.fillMaxWidth())
        }
        item {
            Hint("动作类技能可以用 Gemini 实时看动作，把它的分析粘贴进专项练的记录里。", Modifier.padding(top = 4.dp))
        }
    }
}

private fun planStatus(plan: SkillPlan, matches: List<MatchLog>): String {
    val focus = plan.focus
    return when {
        focus != null && plan.retest is RetestState.Ready -> "复测结果出来了，去决定继续练还是换短板"
        focus != null && plan.retest is RetestState.Collecting -> "当前重点「${focus.name}」练满 7 天，该实战复测了"
        focus != null -> "当前重点「${focus.name}」· 已练 ${plan.drillDates.size} 天" + if (plan.drilledToday) " · 今天练过了" else ""
        plan.skill.template == Template.COMPETITIVE -> {
            val deaths = Diagnosis.deathCount(matches)
            if (deaths < Diagnosis.MIN_DEATHS) "还没有重点。再登记 ${Diagnosis.MIN_DEATHS - deaths} 次阵亡就能诊断短板"
            else "可以诊断了：去看死因占比，定当前重点"
        }
        plan.skill.template == Template.EXAM -> "还没有重点。登记一次模考就能看各模块失分"
        else -> "还没有重点。先写好标准，再选一个子能力开练"
    }
}

@Composable
private fun SkillCard(
    title: String,
    kind: String,
    status: String,
    alert: Boolean,
    onClick: () -> Unit,
    focusDays: Int? = null,
) {
    val p = Paper.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.page, CardShape)
            .border(1.dp, if (alert) p.red else p.divider, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        SectionLabel(kind)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = p.ink, modifier = Modifier.weight(1f))
            if (focusDays != null) {
                Text("$focusDays", style = MaterialTheme.typography.displaySmall, color = if (focusDays >= FocusRules.MIN_DRILL_DAYS) p.red else p.ink)
                Text(" / 7 天", style = MaterialTheme.typography.labelLarge, color = p.ink2, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (alert) p.red else p.ink2)
    }
}
