package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Task
import kotlinx.coroutines.launch

/** 编辑或新建任务时的表单内容。 */
data class TaskForm(
    val title: String = "",
    val goal: String = "",
    val material: String = "",
    val known: String = "",
    val stuck: String = "",
    val nextQuestion: String = "",
)

class TaskEditViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as YishouApp).database.dao()

    var form by mutableStateOf(TaskForm())
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    private var editing: Task? = null
    private var saving = false

    /** taskId 为 null 表示新建。只在第一次进入时加载，旋转屏幕不会覆盖正在改的内容。 */
    fun load(taskId: Long?) {
        if (loaded) return
        if (taskId == null) {
            loaded = true
            return
        }
        viewModelScope.launch {
            val task = dao.getTask(taskId)
            val bp = dao.getBreakpoint(taskId)
            if (task != null) {
                editing = task
                form = TaskForm(
                    title = task.title,
                    goal = task.goal,
                    material = task.material.orEmpty(),
                    known = bp?.known.orEmpty(),
                    stuck = bp?.stuck.orEmpty(),
                    nextQuestion = bp?.nextQuestion.orEmpty(),
                )
            }
            loaded = true
        }
    }

    fun save(onDone: () -> Unit) {
        val f = form
        if (f.title.isBlank() || f.goal.isBlank()) {
            error = "对象和目标都要填"
            return
        }
        if (saving) return
        saving = true
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val material = f.material.trim().ifEmpty { null }
            val old = editing
            if (old == null) {
                dao.createCurrentTask(
                    Task(title = f.title.trim(), goal = f.goal.trim(), material = material, isCurrent = true, createdAt = now),
                    f.known.trim(), f.stuck.trim(), f.nextQuestion.trim(), now,
                )
            } else {
                dao.editTask(
                    old.copy(title = f.title.trim(), goal = f.goal.trim(), material = material),
                    f.known.trim(), f.stuck.trim(), f.nextQuestion.trim(), now,
                )
            }
            saving = false
            onDone()
        }
    }
}

@Composable
fun TaskEditScreen(
    taskId: Long?,
    onBack: () -> Unit,
    vm: TaskEditViewModel = viewModel(),
) {
    LaunchedEffect(taskId) { vm.load(taskId) }
    val f = vm.form

    Scaffold(topBar = { BackTopBar(if (taskId == null) "新建任务" else "编辑任务", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!vm.loaded) return@Column

            SectionTitle("任务")
            Field("对象（例如：行测 · 资料分析）", f.title, singleLine = true) { vm.form = f.copy(title = it) }
            Field("目标", f.goal) { vm.form = f.copy(goal = it) }
            Field("材料摘录（可空）", f.material, minLines = 3) { vm.form = f.copy(material = it) }

            SectionTitle("断点")
            if (taskId != null) {
                Text(
                    "保存修改后，陪练会按新的内容重新出第一手。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Field("已知", f.known) { vm.form = f.copy(known = it) }
            Field("卡点", f.stuck) { vm.form = f.copy(stuck = it) }
            Field("下一问", f.nextQuestion) { vm.form = f.copy(nextQuestion = it) }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.error = null; vm.save(onBack) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (taskId == null) "保存并设为当前任务" else "保存")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}
