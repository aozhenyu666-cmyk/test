package com.behaviordept.app.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.ai.Parsers
import com.behaviordept.app.data.JsonLists
import com.behaviordept.app.data.Rating
import com.behaviordept.app.data.Review
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.data.Transfer
import com.behaviordept.app.today.isReviewDue
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.QuietButton
import com.behaviordept.app.ui.components.RedPenBlock
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UnitDetail(val unit: StudyUnit?, val reviews: List<Review>, val transfers: List<Transfer>)

class UnitDetailViewModel(private val c: AppContainer, unitId: Long) : ViewModel() {
    val state: StateFlow<UnitDetail?> = combine(
        c.study.observeUnit(unitId),
        c.study.observeReviews(unitId),
        c.study.observeTransfers(unitId),
    ) { u, r, t -> UnitDetail(u, r, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long, done: () -> Unit) {
        viewModelScope.launch {
            c.study.delete(id)
            done()
        }
    }
}

@Composable
fun UnitDetailScreen(unitId: Long, onBack: () -> Unit, onContinue: () -> Unit) {
    val vm = appViewModel { UnitDetailViewModel(it, unitId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val p = Paper.colors
    var confirmDelete by remember { mutableStateOf(false) }
    val d = state ?: return
    val u = d.unit
    if (u == null) {
        Column(Modifier.padding(20.dp)) {
            QuietButton("‹ 返回", onBack)
            Hint("这个单元已经删除")
        }
        return
    }
    val step = u.currentStep()
    val due = u.isReviewDue(Time.now())
    val finished = d.reviews.filter { it.finishedAt != null }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QuietButton("‹ 返回", onBack)
        Text(u.title, style = MaterialTheme.typography.headlineLarge, color = p.ink)
        StepGrid(step)

        when {
            due -> InkButton("开始自测", onClick = onContinue)
            step != UnitStep.DONE -> InkButton("继续：第 ${step.number} 步 ${step.title}", onClick = onContinue)
            else -> LineButton("提前自测一次", onClick = onContinue, modifier = Modifier.fillMaxWidth())
        }

        if (u.explainedAt != null) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("间隔自测", Modifier.weight(1f))
                    Text(
                        "当前间隔 ${Spacing.days(u.intervalLevel)} 天",
                        style = MaterialTheme.typography.labelMedium,
                        color = p.ink2,
                    )
                }
                Spacer(Modifier.height(12.dp))
                IntervalTimeline(finished, u.nextReviewAt, u.intervalLevel)
            }
        }

        val pre = u.preQuestionList()
        if (pre.isNotEmpty()) {
            PaperCard {
                SectionLabel("预习问题")
                pre.forEachIndexed { i, q ->
                    Text("${i + 1}. $q", style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (u.explanation.isNotBlank()) {
            PaperCard {
                SectionLabel("合上讲一遍")
                Text(u.explanation, style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.padding(vertical = 8.dp))
                if (u.critique.isNotBlank()) {
                    RedPenBlock(Parsers.sections(u.critique), heading = if (u.critiqueMode == "self") "对照资料自查" else "红笔批改")
                }
            }
        }

        d.transfers.forEach { t ->
            PaperCard {
                SectionLabel("举一反三 · ${Time.md(t.createdAt)}")
                Text(t.example, style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.padding(vertical = 8.dp))
                RedPenBlock(Parsers.sections(t.critique), heading = if (t.mode == "self") "对照资料自查" else "红笔点评")
            }
        }

        finished.asReversed().forEach { r -> ReviewCard(r) }

        PaperCard {
            SectionLabel("资料")
            SelectionContainer {
                Text(u.material, style = MaterialTheme.typography.bodyLarge, color = p.ink, modifier = Modifier.padding(top = 8.dp))
            }
        }

        QuietButton("删除这个单元", onClick = { confirmDelete = true })
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = p.page,
            title = { Text("删除「${u.title}」？", color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("讲解、批改和自测记录会一起删除。事件日志里的记录保留。", color = p.ink2) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(u.id, onBack) }) {
                    Text("删除", color = p.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = p.ink) } },
        )
    }
}

@Composable
private fun ReviewCard(r: Review) {
    val p = Paper.colors
    val questions = JsonLists.decode(r.questions)
    val answers = JsonLists.decode(r.answers)
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("自测 · ${Time.md(r.finishedAt ?: r.createdAt)} · 间隔 ${r.intervalDays} 天", Modifier.weight(1f))
            Text(
                "${Rating.mark(r.rating)} ${Rating.label(r.rating)}",
                style = MaterialTheme.typography.labelLarge,
                color = p.red,
            )
        }
        questions.forEachIndexed { i, q ->
            Text("第${i + 1}题  $q", style = MaterialTheme.typography.titleSmall, color = p.ink, modifier = Modifier.padding(top = 10.dp))
            Text(answers.getOrNull(i).orEmpty(), style = MaterialTheme.typography.bodyMedium, color = p.ink2)
        }
        if (r.critique.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            RedPenBlock(Parsers.sections(r.critique))
        }
    }
}
