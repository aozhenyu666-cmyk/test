package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Job
import com.yishou.app.data.JobMode
import com.yishou.app.data.JobStep
import com.yishou.app.data.StepResult
import com.yishou.app.job.Jobs
import com.yishou.app.settings.AppZone
import com.yishou.app.system.Haptics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JobsViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = (app as YishouApp).database.jobs()

    val jobs: StateFlow<List<Job>> = dao.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val steps: StateFlow<Map<Long, List<JobStep>>> = dao.observeAllSteps().map { xs -> xs.groupBy { it.jobId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 开一件事，拆好步骤，打开它 */
    fun create(title: String, mode: String, lines: String, first: String, estimate: Int?, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val plan = Jobs.plan(mode, title, lines, first)
        val id = dao.insertJob(Job(title = title.trim(), mode = mode, estimateMinutes = estimate, createdAt = now))
        dao.insertSteps(plan.mapIndexed { i, t -> JobStep(jobId = id, idx = i, text = t) })
        onCreated(id)
    }

    fun delete(j: Job) = viewModelScope.launch {
        dao.deleteSteps(j.id)
        dao.deleteJob(j)
    }
}

/**
 * 做事：一件事一步一步走完。三种拆法——按份数（“10 道题”）、自己列步骤、走一步看一步。
 * 每一步：开始 → 做 → 记结果和一句话 → 下一步。用时自动记，最后和你的估计对照。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onTools: () -> Unit, onDrill: () -> Unit, vm: JobsViewModel = viewModel()) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var lines by rememberSaveable { mutableStateOf("") }
    var first by rememberSaveable { mutableStateOf("") }
    var estimate by rememberSaveable { mutableStateOf("") }
    val mode = picked ?: Jobs.suggestMode(title)
    val count = Jobs.count(title)

    Scaffold(topBar = { BackTopBar("做事", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "一步一步走完一件事：先说这一步做什么，做完记一下结果，再说下一步。用时自动记。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoiceTextField(title, { title = it }, "要做的事（例如：做 10 道资料分析）", Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = mode == JobMode.COUNT,
                                onClick = { picked = JobMode.COUNT },
                                enabled = count != null,
                                label = { Text(count?.let { "按份数（${it.first} ${it.second}）" } ?: "按份数") },
                            )
                            FilterChip(selected = mode == JobMode.LIST, onClick = { picked = JobMode.LIST }, label = { Text("自己列步骤") })
                            FilterChip(selected = mode == JobMode.OPEN, onClick = { picked = JobMode.OPEN }, label = { Text("走一步看一步") })
                        }
                        when (mode) {
                            JobMode.LIST -> VoiceTextField(lines, { lines = it }, "每行一步", Modifier.fillMaxWidth(), minLines = 3)
                            JobMode.OPEN -> VoiceTextField(first, { first = it }, "第一步做什么？", Modifier.fillMaxWidth())
                            else -> Text("会拆成 ${count?.first ?: 0} 步，每做完一${count?.second ?: "步"}点一下。", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            estimate, { estimate = it.filter(Char::isDigit).take(4) },
                            label = { Text("估计总共几分钟（可不填）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val ready = title.isNotBlank() && when (mode) {
                            JobMode.LIST -> lines.isNotBlank()
                            JobMode.COUNT -> count != null
                            else -> true
                        }
                        Button(
                            onClick = {
                                vm.create(title, mode, lines, first, estimate.toIntOrNull()) { id -> onOpen(id) }
                                title = ""; lines = ""; first = ""; estimate = ""; picked = null
                            },
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("开始") }
                    }
                }
            }
            val active = jobs.filter { it.finishedAt == null }
            if (active.isNotEmpty()) {
                item { Text("在做的", style = MaterialTheme.typography.titleMedium) }
                items(active, key = { it.id }) { j -> JobRow(j, steps[j.id].orEmpty(), onClick = { onOpen(j.id) }) }
            }
            val done = jobs.filter { it.finishedAt != null }.take(20)
            if (done.isNotEmpty()) {
                item { Text("做完的", style = MaterialTheme.typography.titleMedium) }
                items(done, key = { it.id }) { j -> JobRow(j, steps[j.id].orEmpty(), onClick = { onOpen(j.id) }) }
            }
            item {
                Column {
                    TextButton(onClick = onDrill) { Text("去粉笔这类 App 刷一组题，回来记练习报告 →") }
                    TextButton(onClick = onTools) { Text("小工具：估个数再对照、比一比价 →") }
                }
            }
        }
    }
}

@Composable
private fun JobRow(j: Job, steps: List<JobStep>, onClick: () -> Unit) {
    val cur = Jobs.current(steps)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MoonPhase(Jobs.progress(steps), 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        j.finishedAt != null -> Jobs.line(Jobs.summary(steps, j.estimateMinutes))
                        cur != null -> "第 ${cur.idx + 1}/${steps.size} 步：${cur.text}"
                        else -> "步都做完了，下一步是什么？"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class JobViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.jobs()
    private val id = MutableStateFlow(0L)

    val job: StateFlow<Job?> = id.flatMapLatest { dao.observeJob(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val steps: StateFlow<List<JobStep>> = id.flatMapLatest { dao.observeSteps(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bind(jobId: Long) {
        id.value = jobId
    }

    val level: com.yishou.app.line.Level get() = yishou.level

    fun start(s: JobStep) = viewModelScope.launch {
        if (s.startedAt == null) dao.updateStep(s.copy(startedAt = System.currentTimeMillis()))
    }

    /** 这一步做完：记结果和一句话，下一步接着开始计时 */
    fun finish(s: JobStep, result: String, note: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        dao.updateStep(s.copy(startedAt = s.startedAt ?: now, doneAt = now, result = result, note = note.trim()))
        Haptics.play(yishou, if (result == StepResult.OK) Haptics.Kind.PASS else Haptics.Kind.REDO)
        // 岔路：没做成 / 卡住时说的那句，当差值进回响队列
        if (com.yishou.app.system.Variant.chalu && (result == StepResult.MISS || result == StepResult.STUCK)) {
            yishou.echo(note, com.yishou.app.data.EchoKind.DELTA, "job")
        }
        dao.steps(s.jobId).sortedBy { it.idx }.firstOrNull { it.doneAt == null && it.idx > s.idx }?.let { next ->
            if (next.startedAt == null) dao.updateStep(next.copy(startedAt = now))
        }
    }

    /** 加下一步（走一步看一步，或者原计划之外多出来的一步），马上开始 */
    fun add(jobId: Long, text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val all = dao.steps(jobId)
        val running = all.any { it.doneAt == null }
        dao.insertStep(JobStep(jobId = jobId, idx = (all.maxOfOrNull { it.idx } ?: -1) + 1, text = text.trim(), startedAt = if (running) null else System.currentTimeMillis()))
    }

    fun close(j: Job, takeaway: String) = viewModelScope.launch {
        dao.updateJob(j.copy(finishedAt = System.currentTimeMillis(), takeaway = takeaway.trim()))
        if (com.yishou.app.system.Variant.chalu) yishou.echo(takeaway, com.yishou.app.data.EchoKind.RULE)
    }

    fun reopen(j: Job) = viewModelScope.launch { dao.updateJob(j.copy(finishedAt = null)) }

    /** 改计划：还没开始的步换成新写的这几行（正在做的那一步不动） */
    fun replan(jobId: Long, text: String) = viewModelScope.launch {
        val all = dao.steps(jobId)
        all.filter { it.doneAt == null && it.startedAt == null }.forEach { dao.deleteStep(it.id) }
        val keep = dao.steps(jobId)
        val base = (keep.maxOfOrNull { it.idx } ?: -1) + 1
        val lines = Jobs.plan(JobMode.LIST, "", text)
        dao.insertSteps(lines.mapIndexed { i, t -> JobStep(jobId = jobId, idx = base + i, text = t, startedAt = if (keep.none { it.doneAt == null } && i == 0) System.currentTimeMillis() else null) })
    }

    private val _report = MutableStateFlow<com.yishou.app.outside.Report?>(null)
    /** 刷题 App 的练习报告读出来的东西（等你确认） */
    val report: StateFlow<com.yishou.app.outside.Report?> = _report
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    fun readReport(uri: android.net.Uri) = viewModelScope.launch {
        if (!yishou.settings.vision.value.isComplete) {
            _busy.value = "还没配识图模型：直接手填下面几个数就行。"
            _report.value = com.yishou.app.outside.Report("", null, null, null)
            return@launch
        }
        _busy.value = "正在读练习报告……"
        val data = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yishou.app.system.ImageEncoder.toDataUrl(yishou, uri) }
        } catch (e: Exception) {
            _busy.value = "读不了这张图：${e.message}"
            return@launch
        }
        when (val r = yishou.vision.readReport(data)) {
            is com.yishou.app.llm.LlmResult.Err -> {
                _busy.value = "没读出来（${r.error.message}），手填也行。"
                _report.value = com.yishou.app.outside.Report("", null, null, null)
            }
            is com.yishou.app.llm.LlmResult.Ok -> {
                _busy.value = null
                _report.value = r.value
            }
        }
    }

    fun manualReport() {
        _report.value = com.yishou.app.outside.Report("", null, null, null)
    }

    /** 确认练习报告：记一条刷题记录，这一步做成，这件事收起 */
    fun saveReport(j: Job, step: JobStep, subject: String, questions: Int, correct: Int?, seconds: Int?) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val name = j.title.substringBefore("：")
        yishou.database.jobs().insertOutside(
            com.yishou.app.data.Outside(
                app = name, subject = subject, questions = questions, correct = correct,
                seconds = seconds ?: step.startedAt?.let { ((now - it) / 1000).toInt() }, createdAt = now, startedAt = step.startedAt, jobId = j.id,
            ),
        )
        val note = buildList {
            add("$questions 题")
            correct?.let { add("对 $it（${it * 100 / maxOf(questions, 1)}%）") }
            seconds?.let { add("用时 ${Jobs.showDuration(it * 1000L)}") }
        }.joinToString("，")
        dao.updateStep(step.copy(startedAt = step.startedAt ?: now, doneAt = now, result = StepResult.OK, note = note))
        dao.updateJob(j.copy(finishedAt = now))
        _report.value = null
        _busy.value = null
    }
}

/** 一件事的流程页：轨道上是还没轮到的步，太阳是现在这一步，做完的连成星座。 */
@Composable
fun JobScreen(jobId: Long, onBack: () -> Unit, vm: JobViewModel = viewModel()) {
    LaunchedEffect(jobId) { vm.bind(jobId) }
    val job by vm.job.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    var closing by remember { mutableStateOf(false) }
    val j = job
    val summary = remember(steps, j) { Jobs.summary(steps, j?.estimateMinutes) }

    Scaffold(topBar = { BackTopBar(j?.title ?: "做事", onBack) }) { padding ->
        if (j == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { VortexLoader("") }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoonPhase(Jobs.progress(steps), 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(Jobs.line(summary), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            item { OrbitFlow(steps) }
            if (j.finishedAt == null) {
                item { CurrentStep(j, steps, vm, onClose = { closing = true }) }
                val upcoming = steps.filter { it.doneAt == null }.drop(1).take(4)
                if (upcoming.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("接下来", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            upcoming.forEach { Text("· ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                            val more = steps.count { it.doneAt == null } - 1 - upcoming.size
                            if (more > 0) Text("…还有 $more 步", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item { Finished(j, summary, onReopen = { vm.reopen(j) }) }
            }
            val done = steps.filter { it.doneAt != null }
            if (done.isNotEmpty()) {
                item { Text("走过的", style = MaterialTheme.typography.titleMedium) }
                item { StepBars(done) }
            }
        }
    }

    if (closing && j != null) {
        var note by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { closing = false },
            title = { Text("这件事做完了") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Jobs.line(summary), style = MaterialTheme.typography.bodyMedium)
                    VoiceTextField(note, { note = it }, "收口一句：下次做这类事，先做什么", Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { closing = false; vm.close(j, note) }) { Text("收起这件事") } },
            dismissButton = { TextButton(onClick = { closing = false }) { Text("还没完") } },
        )
    }
}

@Composable
private fun CurrentStep(j: Job, steps: List<JobStep>, vm: JobViewModel, onClose: () -> Unit) {
    val cur = Jobs.current(steps)
    var note by rememberSaveable(cur?.id) { mutableStateOf("") }
    var next by rememberSaveable(steps.size) { mutableStateOf("") }
    var replanning by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cur == null) {
                Text("这几步都走完了。", style = MaterialTheme.typography.titleMedium)
                VoiceTextField(next, { next = it }, "下一步是什么？", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.add(j.id, next); next = "" }, enabled = next.isNotBlank()) { Text("接着做这一步") }
                    OutlinedButton(onClick = onClose) { Text("这件事做完了") }
                }
                return@Column
            }
            Text("第 ${cur.idx + 1} / ${steps.size} 步", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            Text(cur.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            val started = cur.startedAt
            if (started == null) {
                Button(onClick = { vm.start(cur) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("开始这一步") }
                return@Column
            }
            Ticker(started, perStep = j.estimateMinutes?.let { it * 60_000L / maxOf(steps.size, 1) })
            if (j.mode == JobMode.APP) {
                AppStep(j, cur, vm)
                return@Column
            }
            // 做完一步要说一句判断（强度“轻”时可以不写）：不是流水账，是这一步的思考
            val level = vm.level
            val ask = Jobs.REFLECT[cur.idx % Jobs.REFLECT.size]
            val rules = com.yishou.app.line.TalkRules.of(level).copy(budget = 200, structureEvery = 0)
            val check = com.yishou.app.line.Talk.check(note, rules)
            val needs = level != com.yishou.app.line.Level.LIGHT
            val ready = !needs || check.ok
            Text(ask, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            VoiceTextField(note, { note = it }, if (needs) "一句判断，先说结论" else "一句判断（可不填）", Modifier.fillMaxWidth())
            if (note.isNotBlank() || needs) TalkMeter(note, rules)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.finish(cur, StepResult.OK, note) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("做成了") }
                OutlinedButton(onClick = { vm.finish(cur, StepResult.MISS, note) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("没做成") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.finish(cur, StepResult.STUCK, note) }, enabled = ready, modifier = Modifier.weight(1f)) { Text("卡住了") }
                TextButton(onClick = { vm.finish(cur, StepResult.SKIP, note) }, modifier = Modifier.weight(1f)) { Text("跳过") }
            }
            if (j.mode != JobMode.COUNT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceTextField(next, { next = it }, "想到后面还要做的一步", Modifier.weight(1f))
                    TextButton(onClick = { vm.add(j.id, next); next = "" }, enabled = next.isNotBlank()) { Text("加上") }
                }
            }
            Row {
                if (steps.count { it.doneAt == null && it.startedAt == null } > 0 || j.mode != JobMode.COUNT) {
                    TextButton(onClick = { replanning = true }) { Text("改计划") }
                }
                TextButton(onClick = onClose) { Text("提前收起这件事") }
            }
        }
    }
    if (replanning) {
        var text by rememberSaveable { mutableStateOf(steps.filter { it.doneAt == null && it.startedAt == null }.sortedBy { it.idx }.joinToString("\n") { it.text }) }
        AlertDialog(
            onDismissRequest = { replanning = false },
            title = { Text("改计划") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("后面还没开始的步，一行一步。正在做的这一步不动。", style = MaterialTheme.typography.bodySmall)
                    VoiceTextField(text, { text = it }, "接下来的几步", Modifier.fillMaxWidth(), minLines = 4)
                }
            },
            confirmButton = { TextButton(onClick = { replanning = false; vm.replan(j.id, text) }) { Text("就这样") } },
            dismissButton = { TextButton(onClick = { replanning = false }) { Text("不改了") } },
        )
    }
}

/** 去刷题 App 做的那一步：回那边接着做；做完截练习报告，读出来确认后记下 */
@Composable
private fun AppStep(j: Job, step: JobStep, vm: JobViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val report by vm.report.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val name = j.title.substringBefore("：")
    val target = j.app
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::readReport)
    }
    if (target != null) {
        OutlinedButton(onClick = {
            val app = if (target.startsWith("http")) com.yishou.app.outside.AiApp(name, null, target) else com.yishou.app.outside.AiApp(name, target, null)
            Launch.open(context, app)
        }, modifier = Modifier.fillMaxWidth()) { Text("回到$name 接着做") }
    }
    busy?.let { if (it.endsWith("……")) VortexLoader(it) else Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    val r = report
    if (r == null) {
        Text("做完了？在$name 的练习报告页截一张图（题数、正确率、用时都在上面），选它。", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                com.yishou.app.system.ExternalGrace.open()
                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.weight(1f)) { Text("选练习报告截图") }
            OutlinedButton(onClick = vm::manualReport, modifier = Modifier.weight(1f)) { Text("手填") }
        }
        return
    }
    val defaultCount = Regex("(\\d+)\\s*题").find(j.title)?.groupValues?.get(1).orEmpty()
    var subject by rememberSaveable(r) { mutableStateOf(r.subject.ifEmpty { j.title.substringAfter("：").substringBeforeLast(" ").trim() }) }
    var qs by rememberSaveable(r) { mutableStateOf(r.questions?.toString() ?: defaultCount) }
    var right by rememberSaveable(r) { mutableStateOf(r.correct?.toString().orEmpty()) }
    var time by rememberSaveable(r) { mutableStateOf(r.seconds?.let { Jobs.showDuration(it * 1000L) }.orEmpty()) }
    Text("核对一下读出来的数，不对就改：", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(subject, { subject = it }, label = { Text("科目 / 题型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(qs, { qs = it.filter(Char::isDigit) }, label = { Text("题数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(right, { right = it.filter(Char::isDigit) }, label = { Text("做对") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    OutlinedTextField(time, { time = it }, label = { Text("用时（如 18分30秒，不填就按一手记的）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val q = qs.toIntOrNull()
    Button(
        onClick = { if (q != null) vm.saveReport(j, step, subject.trim(), q, right.toIntOrNull()?.coerceAtMost(q), com.yishou.app.outside.Outsides.seconds(time)) },
        enabled = q != null && q > 0,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("记下这一组") }
}

/** 这一步用了多久；有估计时显示每步大约该用多久 */
@Composable
private fun Ticker(startedAt: Long, perStep: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val spent = (now - startedAt).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Jobs.showDuration(spent), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
        perStep?.let {
            Spacer(Modifier.width(10.dp))
            Text(
                "每步大约 ${Jobs.showDuration(it)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (spent > it) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Finished(j: Job, s: Jobs.Summary, onReopen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("做完了 · ${AppZone.format("M月d日 HH:mm", j.finishedAt ?: 0)}", style = MaterialTheme.typography.titleMedium)
            Text(Jobs.line(s), style = MaterialTheme.typography.bodyMedium)
            s.slowest?.let { st -> Jobs.duration(st)?.let { Text("最慢的一步：${st.text}（${Jobs.showDuration(it)}）", style = MaterialTheme.typography.bodySmall) } }
            if (j.takeaway.isNotBlank()) Text("收口：${j.takeaway}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onReopen) { Text("还没完，接着做") }
        }
    }
}

/** 每一步一根横条：长短是用时，颜色是结果 */
@Composable
private fun StepBars(done: List<JobStep>) {
    val max = done.mapNotNull { Jobs.duration(it) }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val ink = MaterialTheme.colorScheme.onBackground
    val miss = MaterialTheme.colorScheme.error
    val faint = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        done.sortedBy { it.idx }.forEach { s ->
            val d = Jobs.duration(s) ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(10.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val color = when (s.result) {
                            StepResult.OK -> ink.copy(alpha = 0.75f)
                            StepResult.SKIP -> faint
                            else -> miss.copy(alpha = 0.8f)
                        }
                        drawRoundRect(color, size = size.copy(width = size.width * (d.toFloat() / max).coerceIn(0.03f, 1f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(Jobs.showDuration(d), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
            }
            if (s.note.isNotBlank()) Text("　${StepResult.label(s.result)}：${s.note}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
