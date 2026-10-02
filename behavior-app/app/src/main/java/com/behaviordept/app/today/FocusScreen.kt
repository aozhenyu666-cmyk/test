package com.behaviordept.app.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.Divider
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.RedPenBlock
import com.behaviordept.app.ui.components.TianZiGeCell
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import com.behaviordept.app.util.Time
import kotlinx.coroutines.delay

/**
 * 专注模式：全屏，只有当前任务、计时和结束按钮。训练内容就在这里面做完。
 */
@Composable
fun FocusScreen(unitId: Long, onExit: () -> Unit) {
    val vm = appViewModel { FocusViewModel(it, unitId) }
    val p = Paper.colors
    var confirmEnd by remember { mutableStateOf(false) }

    val endNow: () -> Unit = { vm.end { onExit() } }
    val requestEnd: () -> Unit = {
        // 刚做完一步时直接结束；做到一半时先确认一下。
        if (vm.completion != null || vm.task == null) {
            endNow()
        } else {
            confirmEnd = true
        }
    }
    BackHandler(enabled = !vm.ended) { requestEnd() }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        FocusTopBar(vm, onEnd = requestEnd)
        Divider()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val completion = vm.completion
            when {
                vm.task == null -> Text("准备中…", color = p.ink2)
                completion != null -> CompletionView(vm, completion, onEnd = endNow)
                else -> when (val t = vm.task) {
                    FocusTask.NewUnit -> NewUnitStep(vm)
                    FocusTask.Review -> ReviewStep(vm)
                    is FocusTask.Step -> when (t.step) {
                        UnitStep.PREVIEW -> PreviewStep(vm)
                        UnitStep.STUDY -> StudyStep(vm)
                        UnitStep.EXPLAIN -> ExplainStep(vm)
                        UnitStep.TRANSFER -> TransferStep(vm)
                        UnitStep.DONE -> Text("这个单元四步都走完了", color = p.ink2)
                    }
                    null -> Unit
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            containerColor = p.page,
            title = { Text("结束这次专注？", style = MaterialTheme.typography.titleLarge, color = p.ink) },
            text = {
                Text(
                    "已专注 ${Time.clock(Time.now() - vm.startedAt)}，会记进今天的训练。没做完的这一步，下次打开会接着做。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; endNow() }) {
                    Text("结束", color = p.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text("继续专注", color = p.ink) }
            },
        )
    }
}

@Composable
private fun FocusTopBar(vm: FocusViewModel, onEnd: () -> Unit) {
    val p = Paper.colors
    var now by remember { mutableLongStateOf(Time.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Time.now()
            delay(1000)
        }
    }
    val (kind, title) = when (val t = vm.task) {
        null -> "专注" to ""
        FocusTask.NewUnit -> "新建" to (vm.unit?.title ?: "学习单元")
        FocusTask.Review -> "间隔自测" to vm.unit?.title.orEmpty()
        is FocusTask.Step -> "第 ${t.step.number} 步 · ${t.step.title}" to vm.unit?.title.orEmpty()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.page)
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kind, style = MaterialTheme.typography.labelMedium, color = p.ink2)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Time.clock(now - vm.startedAt),
            fontFamily = SerifSC,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.headlineMedium,
            color = p.ink,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        TextButton(onClick = onEnd) {
            Text("结束", style = MaterialTheme.typography.labelLarge, color = p.red)
        }
    }
}

/** 一步做完：红笔打勾 + 当场的反馈 + 结束按钮。 */
@Composable
private fun CompletionView(vm: FocusViewModel, completion: Completion, onEnd: () -> Unit) {
    val p = Paper.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        TianZiGeCell(done = true, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(completion.title, style = MaterialTheme.typography.headlineMedium, color = p.ink)
            if (completion.detail.isNotBlank()) {
                Text(completion.detail, style = MaterialTheme.typography.bodyMedium, color = p.ink2)
            }
        }
    }
    vm.critique?.let { RedPenBlock(it, heading = if (vm.selfCheck) "对照资料自查" else "红笔批改") }
    vm.grade?.let { GradeBlock(vm) }
    Spacer(Modifier.height(8.dp))
    InkButton("结束专注", onClick = onEnd)
    if (completion.hasNext) {
        val next = vm.unit?.currentStep()
        if (next != null && next != UnitStep.DONE) {
            LineButton("接着做第 ${next.number} 步：${next.title}", onClick = { vm.continueNext() }, modifier = Modifier.fillMaxWidth())
        }
    }
}
