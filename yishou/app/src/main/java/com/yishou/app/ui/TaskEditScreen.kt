package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.ExternalGrace
import com.yishou.app.system.ImageEncoder
import com.yishou.app.training.PracticeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 编辑或新建任务时的表单内容。 */
data class TaskForm(
    val title: String = "",
    val goal: String = "",
    val material: String = "",
    val known: String = "",
    val stuck: String = "",
    val nextQuestion: String = "",
    val mode: PracticeMode = PracticeMode.THINK,
)

class TaskEditViewModel(app: Application) : AndroidViewModel(app) {

    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()
    var importing by mutableStateOf(false)
        private set

    var form by mutableStateOf(TaskForm())
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    private var editing: Task? = null
    private var saving = false

    /** taskId 为 null 表示新建。只在第一次进入时加载，旋转屏幕不会覆盖正在改的内容。 */
    fun load(taskId: Long?, mode: PracticeMode, sharedText: String?, sharedImage: Uri?) {
        if (loaded) return
        if (taskId == null) {
            form = form.copy(mode = mode, material = sharedText.orEmpty())
            loaded = true
            sharedImage?.let(::importImage)
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
                    mode = PracticeMode.of(task.practiceMode),
                )
            }
            loaded = true
        }
    }

    private fun appendMaterial(text: String) {
        val combined = listOf(form.material.takeIf { it.isNotBlank() }, text).filterNotNull().joinToString("\n\n")
        if (combined.length > 24_000) { error = "材料超过 24000 字，请先精简或分成几个任务。"; return }
        form = form.copy(material = combined)
    }

    /** 图片只转写成草稿，用户可以改，再保存；不会自动创建题卡。 */
    fun importImage(uri: Uri) {
        if (importing) return
        if (!yishou.settings.vision.value.isComplete) { error = "先到设置填写识图模型；也可以直接粘贴文字。"; return }
        importing = true
        error = null
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { ImageEncoder.toDataUrl(yishou, uri) }
                when (val r = yishou.vision.transcribe(form.title.ifBlank { null }, null, data)) {
                    is LlmResult.Ok -> appendMaterial(r.value)
                    is LlmResult.Err -> error = r.error.message
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "读取图片失败：${e.message}" }
            finally { importing = false }
        }
    }

    /** 只读取用户通过系统选中的 UTF-8 文本，避免把二进制文档当作可读材料。 */
    fun importFile(uri: Uri) {
        if (importing) return
        importing = true
        error = null
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = yishou.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() <= 96_000) {
                            val n = input.read(buffer, 0, minOf(buffer.size, 96_001 - out.size()))
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } ?: error("无法打开文件")
                    require(bytes.size <= 96_000) { "文件太大，请摘录需要的部分。" }
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                }
                require(!text.contains('\u0000')) { "请选择 UTF-8 文本文件。" }
                appendMaterial(text)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "没有导入：${e.message ?: "请选择 UTF-8 的 TXT 或 Markdown 文件"}" }
            finally { importing = false }
        }
    }

    fun save(onDone: () -> Unit) {
        val f = form
        if (f.title.isBlank()) {
            error = "给这件事起个名字即可，目标可以先留空。"
            return
        }
        if (f.material.length > 24_000) { error = "材料较长，请精简到 24000 字以内。"; return }
        if (saving) return
        saving = true
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val material = f.material.trim().ifEmpty { null }
            val old = editing
            if (old == null) {
                dao.createCurrentTask(
                    Task(title = f.title.trim(), goal = f.goal.trim().ifBlank { f.mode.goal }, material = material, isCurrent = true, createdAt = now, practiceMode = f.mode.id),
                    f.known.trim(), f.stuck.trim(), f.nextQuestion.trim(), now,
                )
            } else {
                dao.editTask(
                    old.copy(title = f.title.trim(), goal = f.goal.trim().ifBlank { f.mode.goal }, material = material, practiceMode = f.mode.id),
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
    initialMode: PracticeMode = PracticeMode.THINK,
    sharedText: String? = null,
    sharedImage: Uri? = null,
    vm: TaskEditViewModel = viewModel(),
) {
    LaunchedEffect(taskId) { vm.load(taskId, initialMode, sharedText, sharedImage) }
    val f = vm.form
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importImage) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFile) }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PracticeMode.entries.forEach { mode -> FilterChip(selected = f.mode == mode, onClick = { vm.form = f.copy(mode = mode) }, label = { Text(mode.label) }) }
            }
            Field("这件事（例如：比较两个观点 / 晚饭买菜）", f.title, singleLine = true) { vm.form = f.copy(title = it) }
            Field("希望得到什么（可空）", f.goal) { vm.form = f.copy(goal = it) }
            Field("材料、截图转写或当前情况（可空）", f.material, minLines = 3) { vm.form = f.copy(material = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !vm.importing, onClick = { ExternalGrace.open(); photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("导入截图 / 图片") }
                TextButton(enabled = !vm.importing, onClick = { ExternalGrace.open(); file.launch(arrayOf("text/*")) }) { Text("TXT / Markdown") }
            }
            Text("也可以直接粘贴文字，或从其他应用分享文字、截图到一手。导入后请检查并编辑识别结果。", style = MaterialTheme.typography.bodySmall)
            if (vm.importing) MatrixLoader("正在读取材料")

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
            Button(onClick = { vm.error = null; vm.save(onBack) }, enabled = !vm.importing, modifier = Modifier.fillMaxWidth()) {
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
