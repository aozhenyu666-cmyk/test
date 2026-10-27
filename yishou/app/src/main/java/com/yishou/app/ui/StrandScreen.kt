package com.yishou.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.thread.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class StrandViewModel(app: Application) : AndroidViewModel(app) {
    val yishou = app as YishouApp
    val work = StrandWork(yishou)
    var revision by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var note by mutableStateOf<String?>(null); private set
    var proposed by mutableStateOf<List<String>?>(null); private set
    private var job: Job? = null
    init { run { work.open() } }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; note = null
        job = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { note = "这步没能保存，请重试"; com.yishou.app.log.RunLog.e("线程", "页面操作失败", e) }
            finally { busy = false; revision++ }
        }
    }
    fun cancel() { job?.cancel() }
    fun propose(goal: String) = run {
        proposed = StrandEngine.proposeItems(goal, yishou.asker("线程规划")) ?: emptyList()
    }
    fun create(goal: String, done: String, items: List<String>) = run {
        when (val c = StrandEngine.create(goal, done, items, System.currentTimeMillis())) {
            is StrandEngine.Created.Bad -> note = c.why
            is StrandEngine.Created.Ok -> work.load(StrandStore(yishou.database.strands()).save(c.strand))
        }
    }
    fun changed() { revision++ }
}

@Composable
fun StrandScreen(onBack: () -> Unit, vm: StrandViewModel = viewModel()) {
    val context = LocalContext.current
    val revision = vm.revision
    var step by rememberSaveable { mutableIntStateOf(1) }
    var goal by rememberSaveable { mutableStateOf("") }
    var items by rememberSaveable { mutableStateOf(listOf("", "")) }
    var done by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(vm.proposed) { vm.proposed?.let { if (it.isNotEmpty()) items = it; step = 2 } }
    Scaffold(topBar = { BackTopBar("想一件事", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = vm.work.strand
            if (s == null) {
                when (step) {
                    1 -> {
                        OutlinedTextField(goal, { goal = it }, label = { Text("想定哪件事，一句话") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.propose(goal) }, enabled = !vm.busy && goal.isNotBlank()) { Text("列待定项") }
                        TextButton(onClick = { step = 2 }, enabled = !vm.busy) { Text("我自己列") }
                    }
                    2 -> {
                        Text("先定哪几项（2–5 项）")
                        items.forEachIndexed { i, text ->
                            Row {
                                OutlinedTextField(text, { next -> items = items.mapIndexed { j, old -> if (i == j) next else old } }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { items = items.filterIndexed { j, _ -> i != j } }, enabled = items.size > 2) { Text("删") }
                            }
                        }
                        TextButton(onClick = { items = items + "" }, enabled = items.size < 5) { Text("加一项") }
                        Button(onClick = { step = 3 }) { Text("下一步") }
                        TextButton(onClick = { step = 1 }) { Text("改目标") }
                    }
                    else -> {
                        Text(goal)
                        OutlinedTextField(done, { done = it }, label = { Text("做到什么程度，就算定了（自己写）") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.create(goal, done, items) }, enabled = !vm.busy) { Text("开始") }
                        TextButton(onClick = { step = 2 }) { Text("改待定项") }
                    }
                }
            } else {
                StrandHeading(s)
                if (s.done && vm.work.review == null) Text("定了：${s.answer}")
                StrandTurnView(vm.work, vm.busy, revision, vm::changed, { vm.run { vm.work.submit() } },
                    onTrigger = { signal, action -> vm.run {
                        val rev = vm.work.review ?: return@run
                        val why = com.yishou.app.reflex.Reflexes.check(signal, action, rev.q.prompt)
                        if (why == null) {
                            vm.yishou.database.strands().insertReflex(com.yishou.app.data.ReflexRow.from(com.yishou.app.reflex.Reflexes.create(signal, action, rev.q.id, System.currentTimeMillis())))
                            vm.work.next()
                        }
                    } },
                    onNext = { vm.run { vm.work.next() } }, onSplit = {
                        vm.run { goal = vm.work.split(); done = ""; items = listOf("", ""); step = 1 }
                    })
                if (!s.done && vm.work.review == null) {
                    TextButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("交接", Handoff.text(s)))
                        copied = true
                    }) { Text("去外部 AI 聊") }
                    if (copied) {
                        Text("已复制，粘贴给对方即可")
                        Row {
                            listOf("Gemini" to "com.google.android.apps.bard", "ChatGPT" to "com.openai.chatgpt", "Claude" to "com.anthropic.claude").forEach { (name, pkg) ->
                                TextButton(onClick = { runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.let(context::startActivity) } }) { Text(name) }
                            }
                        }
                    }
                    TextButton(onClick = { vm.run { vm.work.returning() } }, enabled = !vm.busy && s.current != null) { Text("带回总结") }
                }
            }
            if (vm.busy) { CircularProgressIndicator(); TextButton(onClick = vm::cancel) { Text("取消") } }
            vm.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun StrandHeading(s: Strand) {
    Text(s.goal, style = MaterialTheme.typography.headlineSmall)
    Text("算定了：${s.doneWhen}")
    s.items.forEach { item ->
        Text((if (item.state == ItemState.SETTLED) "✓ " else if (item.id == s.current?.id) "→ " else "· ") + item.question +
            if (item.conclusion.isNotEmpty()) "：${item.conclusion}" else "", color = if (item.id == s.current?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
