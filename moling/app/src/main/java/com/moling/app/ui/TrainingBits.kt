package com.moling.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.redeem.ChallengeAnswer
import com.moling.core.redeem.ChallengeBundle
import com.moling.core.redeem.UnlockPolicyView
import com.moling.core.workspace.PortResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 以思代锁·墨灵侧入口（第十五批 A3）。题目、判分、通行都在总控；这里只显示总控给的题、把答案交回去、显示回执。
 * 三手棋和聊天里的分数不接到这里。总控没装 / 旧版本 / 策略没开：整行不显示。
 */

/** 今天页的一行：“今天还能用训练换 N 次”+「去换」。拿不到策略或没开就什么都不画。 */
@Composable
fun TrainingLine(refreshKey: Any? = null) {
    var policy by remember { mutableStateOf<UnlockPolicyView?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        policy = (BrainHost.trainingPolicy() as? PortResult.Ok)?.value
    }
    val p = policy ?: return
    if (!p.enabled || p.allowedPackages.isEmpty() || p.remainingPassesToday == 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "今天还能用训练换 ${p.remainingPassesToday} 次（每次 ${p.minutesPerPass} 分钟，只在挣来的娱乐用完时）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { open = true }) { Text("去换") }
    }
    if (open) TrainingDialog(p) { open = false }
}

@Composable
fun TrainingDialog(policy: UnlockPolicyView, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pkg by remember { mutableStateOf(policy.allowedPackages.singleOrNull()) }
    var bundle by remember { mutableStateOf<ChallengeBundle?>(null) }
    var picked by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var left by remember { mutableLongStateOf(0L) }

    val b = bundle
    if (b != null) {
        LaunchedEffect(b.challengeId) {
            while (true) {
                left = (b.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
                if (left <= 0) break
                delay(1_000)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("做一局训练") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    b == null && pkg == null -> {
                        Text("换哪个应用的时间？", style = MaterialTheme.typography.bodyMedium)
                        policy.allowedPackages.sorted().forEach { p ->
                            OutlinedButton(onClick = { pkg = p }, modifier = Modifier.fillMaxWidth()) { Text(appLabel(context, p)) }
                        }
                    }
                    b == null -> {
                        Text("4 道题（心算、数列、百分比、推理），对 3 道就换 ${policy.minutesPerPass} 分钟「${appLabel(context, pkg!!)}」。题目由总控出、总控判。", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text(if (left > 0) "还剩 ${left / 60_000}:${"%02d".format((left / 1000) % 60)}" else "超时了，这一局作废", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        b.questions.forEach { q ->
                            Text(q.stem, style = MaterialTheme.typography.bodyMedium)
                            q.options.forEach { o ->
                                if (picked[q.id] == o.id) Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(o.text) }
                                else OutlinedButton(onClick = { picked = picked + (q.id to o.id) }, modifier = Modifier.fillMaxWidth()) { Text(o.text) }
                            }
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                b == null && pkg != null -> TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        when (val r = BrainHost.trainingStart(pkg!!)) {
                            is PortResult.Ok -> { bundle = r.value; picked = emptyMap(); message = null }
                            is PortResult.Failed -> message = r.error.message
                        }
                        busy = false
                    }
                }) { Text(if (busy) "在要题…" else "开始") }
                b != null -> TextButton(enabled = !busy && left > 0 && picked.size == b.questions.size, onClick = {
                    busy = true
                    scope.launch {
                        val answers = picked.map { (q, o) -> ChallengeAnswer(q, o) }
                        message = when (val r = BrainHost.trainingSubmit(answers)) {
                            is PortResult.Ok -> r.value.message.ifBlank { if (r.value.pass != null) "过了" else "没过" }
                            is PortResult.Failed -> r.error.message
                        }
                        bundle = null
                        busy = false
                    }
                }) { Text("交卷") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(if (b != null) "不做了" else "关") } },
    )
}

private fun appLabel(context: Context, pkg: String): String =
    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
