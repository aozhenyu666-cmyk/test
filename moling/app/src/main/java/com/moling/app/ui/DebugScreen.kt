package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.BrainState
import com.moling.core.ProposalState
import com.moling.core.link.ZkStatus
import com.moling.core.mind.BEIJING
import com.moling.core.mind.MindState
import com.moling.core.mind.dayKey
import com.moling.core.power.Tier
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 调试页（M8）：只读，不写状态、不调模型、不清日志。
 * 把大脑此刻的状态拼成一段等宽文字，真机上出问题时点“复制全部”发给 Claude 看。
 * 报告是现算的：心跳时间、导演快照不在 BrainState 里，没法只靠 state 更新。
 */
@Composable
fun DebugScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by BrainHost.state.collectAsState()

    // 只读也兜一层：任何一处取数出错都显示一行原因，页面本身不能崩。
    val report = runCatching {
        buildReport(
            state = state,
            now = System.currentTimeMillis(),
            tier = BrainHost.tier(),
            mind = BrainHost.directorSnapshot(),
            heartbeatAt = BrainHost.lastHeartbeatAt(),
        )
    }.getOrElse { "调试信息生成失败：${it.message ?: it.javaClass.simpleName}" }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("调试", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context.applicationContext, "复制了", Toast.LENGTH_SHORT).show()
                },
            ) { Text("复制全部") }
        }
        Text(
            text = report,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/** 六块：权柄 / 总控 / 提议 / 导演 / 后台 / 日志。每块前空一行。 */
private fun buildReport(
    state: BrainState,
    now: Long,
    tier: Tier,
    mind: MindState,
    heartbeatAt: Long,
): String = buildString {
    val day = dayKey(now)

    // 1. 权柄
    appendLine()
    appendLine("【权柄】")
    appendLine("等级：${tier.label}")
    val trust = state.trust
    appendLine("接受：${trust.accepted}/${trust.proposed}")
    appendLine("猜对：${trust.guessRight}/${trust.guessJudged}")
    appendLine("Brier：${if (trust.brier < 0) "还没有" else "%.3f".format(trust.brier)}")
    appendLine("连续天数：${trust.days}")

    // 2. 总控
    appendLine()
    appendLine("【总控】")
    val zk = state.lastStatus
    if (zk == null) {
        appendLine("连不上")
    } else {
        zkFields(zk).forEach { appendLine(it) }
        appendLine("最后读到：${if (zk.at > 0) hms(zk.at) else "—"}")
    }

    // 3. 提议
    appendLine()
    appendLine("【提议】")
    val counts = state.proposals.groupingBy { it.state }.eachCount()
    ProposalState.entries.forEach { appendLine("${it.label}：${counts[it] ?: 0}") }
    val recent = state.proposals.sortedByDescending { it.createdAt }.take(10)
    if (recent.isEmpty()) {
        appendLine("最近 10 条：还没有")
    } else {
        appendLine("最近 10 条：")
        recent.forEach { p ->
            appendLine("· ${p.text}")
            appendLine("  状态：${p.state.label}｜结局：${p.zkOutcome.ifBlank { "—" }}｜回执：${p.receipt.ifBlank { "—" }}")
        }
    }

    // 4. 导演
    appendLine()
    appendLine("【导演】")
    val baton = mind.baton
    if (baton == null) {
        appendLine("没有接力棒")
    } else {
        appendLine("接力棒：${baton.question}")
        appendLine("下一问：${baton.nextAsk.ifBlank { "—" }}")
    }
    appendLine("议程：${mind.agenda.size} 条")
    appendLine("今天打扰：${if (mind.pushesDay == day) mind.pushesToday else 0} 次")
    val cost = mind.costToday(day)
    appendLine("模型花费：调用 ${cost.calls} 次 / 输入 ${cost.charsIn} 字 / 输出 ${cost.charsOut} 字 / 失败 ${cost.failures} 次")

    // 5. 后台（M9/M10 接上心跳和前台应用后由 Lead 补数据，这里先留—）
    appendLine()
    appendLine("【后台】")
    appendLine("心跳上次：${if (heartbeatAt > 0) hms(heartbeatAt) else "还没跑过"}")
    appendLine("看前台应用：—")
    appendLine("前台包名：—")
    appendLine("类别：—")
    appendLine("连续分钟：—")

    // 6. 日志（最新在上）
    appendLine()
    appendLine("【日志】")
    if (state.log.isEmpty()) {
        appendLine("（还没有日志）")
    } else {
        state.log.asReversed().forEach { appendLine("· $it") }
    }
}.trimEnd()

/**
 * ZkStatus 的字段一条一行。不逐字段写死：以后 core 加字段，调试页自动跟上。
 * 列表字段里也有逗号，会被拆成两行——调试页够用，不为此解析。
 */
private fun zkFields(s: ZkStatus): List<String> =
    s.toString().removePrefix("ZkStatus(").removeSuffix(")").split(", ").filter { it.isNotBlank() }

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 北京时间 HH:mm:ss。 */
private fun hms(at: Long): String = Instant.ofEpochMilli(at).atZone(BEIJING).format(HMS)
