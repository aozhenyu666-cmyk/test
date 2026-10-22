package com.zongkong.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.ChangeOp
import com.zongkong.core.Policy
import com.zongkong.core.moling.Training
import com.zongkong.core.moling.TrainingChallenge
import kotlinx.coroutines.delay

/*
 * 以思代锁（第十五批 A1 / A2，总控侧界面）。规则全在 core/moling/Training.kt，这里只显示和调用：
 *  - 设置页：读排队中的设置（Policy.desiredTraining），保存走 Policy.setTraining（开启 / 放宽 24 小时后生效，收紧立即）；
 *  - 拦截页：只有“挣来的娱乐用完了”是唯一原因、策略开着、这个应用在名单里时，才出现「做一局训练」；
 *  - 题目里的标准答案只在交卷时用，界面上绝不显示。
 */

private const val MAX_PER_PASS = Training.MAX_MINUTES_PER_PASS

@Composable
fun TrainingSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val desired = remember(config.training, config.pending) { Policy.desiredTraining(config) }
    var enabled by remember(desired) { mutableStateOf(desired.enabled) }
    var packages by remember(desired) { mutableStateOf(desired.packages) }
    var perPass by remember(desired) { mutableStateOf(desired.minutesPerPass.toString()) }
    var passes by remember(desired) { mutableStateOf(desired.dailyMaxPasses.toString()) }
    var minutes by remember(desired) { mutableStateOf(desired.dailyMaxMinutes.toString()) }
    var attempts by remember(desired) { mutableStateOf(desired.attemptsPerDay.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    val pending = config.pending.filter { it.op is ChangeOp.Training }
    val labels = remember(config.blocked) {
        config.blocked.associateWith { pkg ->
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("以思代锁", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint(
                "答几道有标准答案的题（心算、数列、百分比、推理，4 道对 3 道），换一点今天的娱乐分钟。" +
                    "只在“今天挣来的娱乐用完了”时能用；关卡到点、欠着的事、专注锁、墨灵约的严管、每天总上限，都不能用它抵。" +
                    "换来的分钟一小时内有效，用完照旧拦。开启和放宽 24 小时后生效，收紧立即生效。",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开启", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(enabled, onCheckedChange = { enabled = it })
            }
            SectionLabel("哪些应用能换（只列拦截名单里的）")
            if (labels.isEmpty()) Hint("拦截名单是空的。")
            labels.entries.sortedBy { it.value }.forEach { (pkg, label) ->
                val on = pkg in packages
                Row(
                    Modifier.fillMaxWidth().clickable { packages = if (on) packages - pkg else packages + pkg },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, Modifier.weight(1f))
                    Checkbox(checked = on, onCheckedChange = { packages = if (it) packages + pkg else packages - pkg })
                }
            }
            SectionLabel("额度")
            NumberField("每次换几分钟（最多 $MAX_PER_PASS）", perPass, { perPass = it }, Modifier.fillMaxWidth(), "分钟")
            NumberField("每天最多换几次", passes, { passes = it }, Modifier.fillMaxWidth(), "次")
            NumberField("每天最多换几分钟", minutes, { minutes = it }, Modifier.fillMaxWidth(), "分钟")
            NumberField("每天最多开几局（答错、超时都算）", attempts, { attempts = it }, Modifier.fillMaxWidth(), "局")
            Button(
                onClick = {
                    val want = desired.copy(
                        enabled = enabled,
                        packages = packages.filter { it in config.blocked }.toSet(),
                        minutesPerPass = perPass.toIntOrNull() ?: desired.minutesPerPass,
                        dailyMaxPasses = passes.toIntOrNull() ?: desired.dailyMaxPasses,
                        dailyMaxMinutes = minutes.toIntOrNull() ?: desired.dailyMaxMinutes,
                        attemptsPerDay = attempts.toIntOrNull() ?: desired.attemptsPerDay,
                    )
                    store.updateConfig { c -> Policy.setTraining(c, want, System.currentTimeMillis()).also { message = it.message }.config }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
            if (pending.isNotEmpty()) {
                SectionLabel("等待生效")
                pending.forEach { p ->
                    Panel {
                        Text(p.summary, style = MaterialTheme.typography.bodyMedium)
                        Hint("${clock(p.effectiveAt)} 生效；之前可以在“待生效的改动”里撤回。")
                    }
                }
            }
            // 第十五批 A4：最近的训练记录（只显示状态和回执，不显示答案）
            val book by store.training.collectAsStateWithLifecycle()
            if (book.challenges.isNotEmpty()) {
                SectionLabel("最近记录")
                book.challenges.takeLast(10).asReversed().forEach { c ->
                    val r = book.receipts.lastOrNull { it.challengeId == c.id }
                    val pass = book.passes.firstOrNull { it.challengeId == c.id }
                    Hint(
                        "${clock(c.issuedAt)} · ${labels[c.pkg] ?: c.pkg} · " +
                            when (c.state) {
                                com.zongkong.core.moling.ChallengeState.OPEN -> "进行中"
                                com.zongkong.core.moling.ChallengeState.PASSED -> "过了"
                                com.zongkong.core.moling.ChallengeState.FAILED -> "没过"
                                com.zongkong.core.moling.ChallengeState.EXPIRED -> "作废"
                            } +
                            (pass?.let { " · 换了 ${it.minutes} 分钟（到 ${clock(it.expiresAt)}）" } ?: "") +
                            (r?.let { " · ${it.message.take(30)}" } ?: ""),
                    )
                }
            }
            val live = config.training
            Hint("现在生效的：${if (live.enabled) "开着" else "关着"} · ${live.packages.size} 个应用 · 每次 ${live.minutesPerPass} 分钟 · 每天 ${live.dailyMaxPasses} 次 / ${live.dailyMaxMinutes} 分钟 · ${live.attemptsPerDay} 局")
        }
    }
    OutcomeDialog(message) { message = null }
}

/** 拦截页上这个应用此刻能不能做一局（不能就不显示按钮）。 */
fun trainingAvailable(status: com.zongkong.core.Status, policy: com.zongkong.core.moling.TrainingPolicy, pkg: String): Boolean =
    policy.enabled && pkg.isNotBlank() && pkg in policy.packages && Training.blocker(status) == null

/**
 * 拦截页里的一局训练。点「做一局训练」才发题；5 分钟内交卷。交卷后显示总控的回执；
 * 过了就调 [onPassed]（拦截页重新判断，额度已经变了），没过就回到原来的拦截。
 */
@Composable
fun TrainingCard(pkg: String, minutesPerPass: Int, onPassed: () -> Unit) {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    var challenge by remember { mutableStateOf<TrainingChallenge?>(null) }
    var picked by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableStateOf(0L) }

    val c = challenge
    if (c == null) {
        OutlinedButton(
            onClick = {
                val o = store.trainingIssue(pkg)
                if (o.ok) {
                    challenge = o.value
                    picked = emptyMap()
                    message = null
                } else {
                    message = o.message
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("做一局训练（换 $minutesPerPass 分钟）") }
        message?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodySmall) }
        return
    }

    androidx.compose.runtime.LaunchedEffect(c.id) {
        while (true) {
            left = (c.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (left <= 0) break
            delay(1_000)
        }
    }
    Panel(accent = MaterialTheme.colorScheme.primary) {
        Text("一局训练 · 4 道对 3 道", style = MaterialTheme.typography.titleMedium)
        Hint(if (left > 0) "还剩 ${left / 60_000}:${"%02d".format((left / 1000) % 60)}" else "超时了，这一局作废")
        c.questions.forEach { q ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(q.stem, style = MaterialTheme.typography.bodyLarge)
                q.options.forEach { o ->
                    val on = picked[q.id] == o.id
                    if (on) {
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(o.text) }
                    } else {
                        OutlinedButton(onClick = { picked = picked + (q.id to o.id) }, modifier = Modifier.fillMaxWidth()) { Text(o.text) }
                    }
                }
            }
        }
        Button(
            enabled = left > 0 && picked.size == c.questions.size,
            onClick = {
                val r = store.trainingSubmit(c.id, c.nonce, picked)
                message = r.message
                challenge = null
                if (r.state == "PASSED") onPassed()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("交卷") }
        OutlinedButton(onClick = { challenge = null; message = "这一局放弃了（算一局）" }, modifier = Modifier.fillMaxWidth()) { Text("不做了") }
    }
    message?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodySmall) }
}
