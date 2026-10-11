package com.yishou.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.DailySummary
import com.yishou.app.summary.SummaryEngine
import com.yishou.app.summary.SummaryMarkdown
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class SummaryViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()

    val summaries: StateFlow<List<DailySummary>> = dao.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var running by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** 立即总结今天（不用等到点）。 */
    fun runToday() {
        if (running) return
        running = true
        message = null
        viewModelScope.launch {
            val today = WindowClock.today(System.currentTimeMillis(), ZoneId.systemDefault())
            message = when (val r = yishou.summaryEngine.run(today)) {
                is SummaryEngine.Outcome.Saved -> "已生成今天的总结，明天第一问已写进断点"
                is SummaryEngine.Outcome.NoRecord -> "今天还没有任何轮次，记为“当日无记录”"
                is SummaryEngine.Outcome.Failed -> "总结失败：${r.error.message}"
            }
            running = false
        }
    }

    /** 生成导出用的 Markdown。 */
    suspend fun markdown(summary: DailySummary): String {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.parse(summary.date)
        val rounds = dao.roundsBetween(WindowClock.startOfDay(date, zone), WindowClock.startOfDay(date.plusDays(1), zone))
        val task = dao.getCurrentTask()
        val bp = task?.let { dao.getBreakpoint(it.id) }
        return SummaryMarkdown.build(summary, task, bp, rounds, zone)
    }
}

@Composable
fun SummaryScreen(onBack: () -> Unit, vm: SummaryViewModel = viewModel()) {
    val context = LocalContext.current
    val list by vm.summaries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun share(summary: DailySummary) {
        scope.launch {
            val text = vm.markdown(summary)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "一手 ${summary.date} 学习记录")
                .putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "导出到"))
        }
    }

    Scaffold(topBar = { BackTopBar("每晚总结", onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每天到点自动总结，也可以现在手动总结今天。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::runToday, enabled = !vm.running, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.running) "正在总结……" else "立即总结今天")
                    }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (list.isEmpty()) {
                item { Text("还没有总结。") }
            }
            items(list, key = { it.date }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.date, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                "${s.roundCount} 手，有效 ${s.effectiveCount} 手，窗口 ${s.windowMinutes} 分钟",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodyMedium)
                        if (s.rule.isNotBlank()) Text("规则：${s.rule}", style = MaterialTheme.typography.bodyMedium)
                        if (s.tomorrowQuestion.isNotBlank()) {
                            Text("明天第一问：${s.tomorrowQuestion}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s.roundCount > 0) OutlinedButton(onClick = { share(s) }) { Text("导出 Markdown") }
                    }
                }
            }
        }
    }
}
