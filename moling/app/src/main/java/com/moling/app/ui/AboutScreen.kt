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
import com.moling.app.ui.art.inkEnter
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.inkEnter(distance = 8.dp)) {
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

        // ---------------------------------------------------------------- 她摸清的规律（v12）
        val proposedRules = state.rules.filter { it.status == "proposed" }
        val keptRules = state.rules.filter { it.status == "kept" }
        if (proposedRules.isNotEmpty() || keptRules.isNotEmpty()) {
            com.moling.app.ui.art.InkTitle("她摸清的规律")
            Text(
                "你点「对」之后，她才会按这条来找你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            proposedRules.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(com.moling.core.review.Rules.line(r), style = MaterialTheme.typography.bodySmall)
                    if (r.why.isNotBlank()) {
                        Text(
                            "依据：" + r.why,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(onClick = { scope.launch { runCatching { BrainHost.setRule(r.id, true) } } }) { Text("对") }
                        TextButton(onClick = { scope.launch { runCatching { BrainHost.setRule(r.id, false) } } }) { Text("不对") }
                    }
                }
            }
            if (keptRules.isNotEmpty()) {
                Text("她在按这些规律找你", style = MaterialTheme.typography.labelLarge)
                keptRules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            com.moling.core.review.Rules.line(r),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { runCatching { BrainHost.setRule(r.id, false) } } }) {
                            Text("不再用", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 她推过的（v8）
        val thoughts = state.thoughts.takeLast(20).reversed()
        com.moling.app.ui.art.InkTitle("她推过的")
        if (thoughts.isEmpty()) {
            Text("她还没自己推过什么。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val right = thoughts.count { it.outcome == "对了" }
            val wrongN = thoughts.count { it.outcome == "错了" }
            val expired = thoughts.count { it.outcome == "过期" }
            val open = thoughts.count { it.outcome.isBlank() }
            Text(
                "最近 " + thoughts.size + " 条：对了 " + right + "、错了 " + wrongN + "、过期 " + expired + "、还没验证 " + open,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            thoughts.forEach { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (t.outcome.isNotBlank()) {
                            val c = when (t.outcome) {
                                "对了" -> Ink
                                "错了" -> Seal
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(color = c.copy(alpha = 0.10f), shape = MaterialTheme.shapes.small) {
                                Text(
                                    t.outcome,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "把握" + t.confidence,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (t.outcome.isBlank()) {
                            TextButton(onClick = { scope.launch { runCatching { BrainHost.judgeThought(t.id, true) } } }) { Text("对") }
                            TextButton(onClick = { scope.launch { runCatching { BrainHost.judgeThought(t.id, false) } } }) { Text("不对") }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 她怎么出现（v9 反免疫）
        com.moling.app.ui.art.InkTitle("她怎么出现")
        Text(
            "同一种方式被你连着秒划两次，她会让它歇两天，换别的方式来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val habitNow = System.currentTimeMillis()
        val shownModalities = com.moling.core.live.Modality.entries.filter { m ->
            state.habits.marks.any { it.modality == m }
        }
        if (shownModalities.isEmpty()) {
            Text("她还没主动出现过。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            shownModalities.forEach { m ->
                val n = state.habits.marks.count { it.modality == m }
                val works = (com.moling.core.live.Habits.works(state.habits, m) * 100).toInt()
                val cooling = com.moling.core.live.Habits.cooling(state.habits, m, habitNow)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    ModalityIcon(m)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        m.label + "：" + n + " 次，管用 " + works + "%" + if (cooling) "（歇着）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 看板里每种出现方式的小插画。 */
@Composable
private fun ModalityIcon(m: com.moling.core.live.Modality) {
    val s = Modifier.size(22.dp)
    when (m) {
        com.moling.core.live.Modality.PEEK -> com.moling.app.ui.art.InkPapers(s, animated = false)
        com.moling.core.live.Modality.GATE -> com.moling.app.ui.art.InkHourglass(1f, s)
        com.moling.core.live.Modality.WHISPER -> com.moling.app.ui.art.InkRipple(s, animated = false)
        com.moling.core.live.Modality.PET -> MolingFace(com.moling.core.live.Mood.HAPPY, s)
        com.moling.core.live.Modality.STARE -> com.moling.app.ui.art.InkRing(0.8f, s)
        com.moling.core.live.Modality.COUNCIL -> com.moling.app.ui.art.InkBubbles(s, animated = false)
        com.moling.core.live.Modality.QUIZ -> com.moling.app.ui.art.InkGalaxy(s, animated = false)
    }
}