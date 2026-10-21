package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.Act
import com.moling.core.ProposalState
import com.moling.core.mind.BEIJING
import com.moling.core.review.About
import com.moling.core.review.Council
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * “她对你的了解”和复盘（v4）：
 *  - 了解：一份文档，整份放进她的提示词。她夜里提的修改要你点“好”才算数；你可以直接改，每次改动都留版本。
 *  - 周报：每周日夜里自动出一份，也可以现在要一份。
 *  - 四个参谋：大判断前（要不要放弃、换方向）问一句。
 *  - 她给自己定的唤醒：可以取消。
 */
@Composable
fun AboutScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val state by BrainHost.state.collectAsState()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(state.about.ifBlank { About.SEED }) }
    // 她的修改被接受、或从别处改了，编辑框跟着换（只在他没在改的时候）
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(state.about) { if (!dirty) doc = state.about.ifBlank { About.SEED } }
    var busy by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var council by remember { mutableStateOf<Council?>(null) }

    fun toast(msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
    fun hm(at: Long) = Instant.ofEpochMilli(at).atZone(BEIJING).let { "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("她对你的了解 · 复盘", style = MaterialTheme.typography.titleMedium)
        }

        // 一株慢慢长的叶子：连续用的天数（两周长满）
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.moling.app.ui.art.InkLeaf(state.trust.days / 14f, Modifier.size(84.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "一起过了连续 ${state.trust.days} 天。她的提议你接受了 ${state.trust.accepted}/${state.trust.proposed}，" +
                    "她猜对了 ${state.trust.guessRight}/${state.trust.guessJudged}。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ---------------------------------------------------------------- 了解
        com.moling.app.ui.art.InkTitle("她对你的了解")
        Text(
            "整份放进她的提示词。她夜里会提修改，你点“好”才算数；你也可以直接改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.proposals.filter { it.state == ProposalState.PENDING && it.act is Act.About }.forEach { p ->
            val a = p.act as Act.About
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("她想这样改：${a.what}", style = MaterialTheme.typography.bodyMedium)
                    Text(a.text, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                val o = runCatching { BrainHost.accept(p.id, System.currentTimeMillis()) }.getOrNull()
                                busy = false; dirty = false
                                toast(o?.message ?: "没接上，再试一次")
                            }
                        }) { Text("好，就这样") }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch { runCatching { BrainHost.decline(p.id, System.currentTimeMillis()) } }
                        }) { Text("不用") }
                    }
                }
            }
        }
        OutlinedTextField(
            value = doc,
            onValueChange = { if (it.length <= About.MAX_CHARS + 200) { doc = it; dirty = true } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        )
        Button(enabled = dirty && !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { BrainHost.editAbout(doc) }
                busy = false; dirty = false
                toast("改好了，下一句话起她就按这份来")
            }
        }) { Text("保存我的修改") }

        val versions = state.aboutVersions.takeLast(5).reversed()
        if (versions.isNotEmpty()) {
            Text("以前的版本", style = MaterialTheme.typography.titleSmall)
            versions.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${hm(v.at)} · ${v.by} · ${v.text.lines().firstOrNull { it.startsWith("- ") }?.take(24) ?: v.text.take(24)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = !busy, onClick = { doc = v.text; dirty = true }) { Text("用这版") }
                }
            }
        }

        // ---------------------------------------------------------------- 周报
        com.moling.app.ui.art.InkTitle("周报")
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val r = runCatching { BrainHost.week() }.getOrNull()
                busy = false
                if (r == null) toast("没出来，等会儿再试")
            }
        }) { Text(if (busy) "在算…" else "现在出一份") }
        state.weeks.reversed().take(6).forEach { w ->
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${w.week} 这一周", style = MaterialTheme.typography.labelLarge)
                    Text(w.text, style = MaterialTheme.typography.bodyMedium)
                    val perHour = if (w.nudgesPerHour < 0) "—" else "%.1f".format(w.nudgesPerHour)
                    Text(
                        "做事 ${w.workMinutes / 60} 小时 · 娱乐 ${w.playMinutes / 60} 小时 · 偏离 ${w.drifts} 次 · 每做事一小时打扰 $perHour 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- 四个参谋
        com.moling.app.ui.art.InkTitle("四个参谋")
        Text(
            "大判断前问一句（要不要放弃、换方向、今天还写不写）：教练、军师、对手、朋友各说一句，再合成一句。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = question, onValueChange = { question = it.take(200) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("要判断的事") })
        Button(enabled = question.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                council = runCatching { BrainHost.council(question.trim()) }.getOrNull()
                busy = false
                if (council == null) toast("现在问不了（模型没接上）")
            }
        }) { Text(if (busy) "在商量…" else "问问四个参谋") }
        council?.let { c ->
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(c.together, style = MaterialTheme.typography.bodyLarge)
                    listOf("教练" to c.coach, "军师" to c.strategist, "对手" to c.critic, "朋友" to c.friend).forEach { (who, line) ->
                        Text("$who：$line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 唤醒
        if (state.wakes.isNotEmpty()) {
            com.moling.app.ui.art.InkTitle("她给自己定的唤醒")
            state.wakes.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${hm(w.at)}${if (w.daily) "（每天）" else ""} · ${w.note}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { runCatching { BrainHost.cancelWake(w.id) } } }) { Text("取消") }
                }
            }
        }
    }
}
