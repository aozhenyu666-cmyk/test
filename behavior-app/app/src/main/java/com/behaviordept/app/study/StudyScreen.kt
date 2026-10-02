package com.behaviordept.app.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.today.isReviewDue
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StudyOverview(
    val units: List<StudyUnit>,
    val retention: List<Pair<Int, Double>>,
    val ratedCount: Int,
)

class StudyViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<StudyOverview?> = combine(c.study.observeUnits(), c.study.observeAllReviews()) { units, reviews ->
        StudyOverview(
            units = units.sortedWith(
                compareByDescending<StudyUnit> { it.isReviewDue(Time.now()) }
                    .thenBy { it.currentStep() == UnitStep.DONE }
                    .thenByDescending { it.createdAt },
            ),
            retention = retentionPoints(reviews),
            ratedCount = reviews.count { it.rating != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StudyScreen(onOpenUnit: (Long) -> Unit, onNewUnit: () -> Unit) {
    val vm = appViewModel { StudyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val p = Paper.colors

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            PageHeader("学习", "把“看懂了”变成“讲得出、隔几天还答得出”")
        }
        item {
            PaperCard {
                SectionLabel("保持率 · 每个间隔下判定“记得”的占比")
                Spacer(Modifier.height(10.dp))
                if (s.retention.size >= 2) {
                    RetentionChart(s.retention)
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).background(p.paper, CardShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Hint("至少在两个不同间隔上做过自测，这里会画出保持率曲线（已判定 ${s.ratedCount} 次）")
                    }
                }
            }
        }
        if (s.units.isEmpty()) {
            item {
                PaperCard {
                    Text("还没有学习单元", style = MaterialTheme.typography.titleLarge, color = p.ink)
                    Spacer(Modifier.height(6.dp))
                    Hint("新建一个：标题 + 一小块资料，然后按四步走完。")
                }
            }
        }
        items(s.units, key = { it.id }) { unit ->
            UnitRow(unit) { onOpenUnit(unit.id) }
        }
        item {
            LineButton("新建学习单元", onClick = onNewUnit, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UnitRow(unit: StudyUnit, onClick: () -> Unit) {
    val p = Paper.colors
    val step = unit.currentStep()
    val due = unit.isReviewDue(Time.now())
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.page, CardShape)
            .border(1.dp, if (due) p.red else p.divider, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                unit.title,
                style = MaterialTheme.typography.titleLarge,
                color = p.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            val status = when {
                due -> "自测到期"
                step != UnitStep.DONE -> "第 ${step.number} 步 · ${step.title}"
                unit.nextReviewAt != null -> "下次自测 ${Time.md(unit.nextReviewAt)}（${Time.relativeDay(Time.dateOf(unit.nextReviewAt))}）"
                else -> "已完成"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (due) p.red else p.ink2)
        }
        Spacer(Modifier.width(12.dp))
        MiniSteps(step)
    }
}

/** 列表里的迷你步骤格：四个小方块。 */
@Composable
private fun MiniSteps(current: UnitStep) {
    val p = Paper.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        UnitStep.steps.forEach { s ->
            val done = current == UnitStep.DONE || s.number < current.number
            val m = Modifier.size(10.dp)
            Box(
                when {
                    s == current -> m.background(p.ink)
                    done -> m.border(1.5.dp, p.red)
                    else -> m.border(1.dp, p.divider)
                },
            )
        }
    }
}
