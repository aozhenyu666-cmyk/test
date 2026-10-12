package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.training.PracticeMode
import com.yishou.app.training.PracticeSession
import com.yishou.app.ui.theme.StoneMark
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    val practice = PracticeSession(yishou, viewModelScope)
    val tasks = yishou.database.dao().observeAllTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenCards: () -> Unit,
    onEditTask: (Long?, PracticeMode) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val app = LocalContext.current.applicationContext as YishouApp
    val s by vm.practice.state.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        TideBackdrop(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter))
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoneMark(24.dp)
                Spacer(Modifier.size(10.dp))
                Text("一手", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("从眼前，走一步", style = MaterialTheme.typography.headlineMedium)
                    Text("训练判断，也把事情做下去", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TimeWheel(Modifier.size(78.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PracticeMode.entries.forEach { mode ->
                    FilterChip(selected = s.mode == mode, onClick = { vm.practice.selectMode(mode) }, enabled = !s.busy, label = { Text(mode.label) })
                }
            }
            Text(
                if (s.mode == PracticeMode.THINK) "题目、观点、生活情境都能练。一次检查一个判断。" else "从目标和当前情况开始，做完一步，再接实际结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!llm.isComplete) {
                Text("先在设置中填写陪练接口。你仍然可以整理任务和材料。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text("配置陪练") }
            }
            val alternatives = tasks.filter { PracticeMode.of(it.practiceMode) == s.mode && it.id != s.task?.id }.take(3)
            if (alternatives.isNotEmpty()) {
                Text("接续另一件事", style = MaterialTheme.typography.labelLarge)
                alternatives.forEach { task -> TextButton(onClick = { vm.practice.selectTask(task) }, enabled = !s.busy) { Text(task.title) } }
            }
            PracticePanel(vm.practice, onEdit = { onEditTask(it, s.mode) })
            if (s.task != null) TextButton(onClick = { onEditTask(null, s.mode) }, enabled = !s.busy) { Text("换一件事 / 导入新材料") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenCards) { Text("题卡练习") }
                TextButton(onClick = onOpenWindow) { Text("专注陪练窗口") }
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}
