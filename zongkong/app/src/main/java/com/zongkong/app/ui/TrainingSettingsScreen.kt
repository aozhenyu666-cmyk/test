package com.zongkong.app.ui

import android.content.pm.PackageManager
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
import com.zongkong.core.moling.TrainingPolicy

/**
 * 设置 · 以思代锁（A1）：他自己选哪些应用能用训练换今天的娱乐分钟。
 *
 * 所有判断在 core（Training / Policy）：这里只把 desiredTraining（含排队中的改动）摆出来，
 * 保存走 Policy.setTraining，把 Outcome.message 原样显示。收紧立即生效，开启 / 放宽 24 小时。
 */
@Composable
fun TrainingSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val desired = remember(config.pending, config.training) { Policy.desiredTraining(config) }
    val blocked = remember(config.blocked) { config.blocked.sorted() }

    var enabled by remember { mutableStateOf(desired.enabled) }
    var selected by remember { mutableStateOf(desired.packages) }
    var minutes by remember { mutableStateOf(desired.minutesPerPass.toString()) }
    var passes by remember { mutableStateOf(desired.dailyMaxPasses.toString()) }
    var totalMin by remember { mutableStateOf(desired.dailyMaxMinutes.toString()) }
    var attempts by remember { mutableStateOf(desired.attemptsPerDay.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    val now = rememberNow()
    val pending = config.pending.filter { it.op is ChangeOp.Training }
    val effective = config.training

    Column(Modifier.fillMaxSize()) {
        BackBar("以思代锁", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint(
                "答几道有标准答案的题，换一点今天的娱乐分钟。只在挣来的娱乐用完时能用；别的拦截（关卡、欠着的事、专注锁……）不能用它抵。",
            )
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("打开训练兑换", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (effective.enabled) "开着：每次换 ${effective.minutesPerPass} 分钟，每天最多 ${effective.dailyMaxPasses} 次 / ${effective.dailyMaxMinutes} 分钟"
                            else "关着：拦截页不出现「做一局训练」",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalSignals.current.muted,
                        )
                    }
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                }
                Hint("开启、放宽（多选应用 / 加分钟 / 加次数）要等 24 小时；关掉、少选应用、减分钟立即生效。")
            }

            SectionLabel("能换的应用（只列拦截名单里的娱乐应用）")
            if (blocked.isEmpty()) {
                Hint("拦截名单是空的。先去「拦截名单」勾上娱乐应用，这里才有可选项。")
            } else {
                Panel {
                    blocked.forEach { pkg ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(appLabel(context.packageManager, pkg), style = MaterialTheme.typography.bodyLarge)
                                Text(pkg, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
                            }
                            Checkbox(
                                checked = pkg in selected,
                                onCheckedChange = { on -> selected = if (on) selected + pkg else selected - pkg },
                            )
                        }
                    }
                }
            }

            SectionLabel("额度（都由总控判，墨灵改不了）")
            NumberField("每次换几分钟（1–15）", minutes, { minutes = it }, Modifier.fillMaxWidth(), "分钟")
            NumberField("每天最多换几次（0–6）", passes, { passes = it }, Modifier.fillMaxWidth(), "次")
            NumberField("每天最多换几分钟（0–60）", totalMin, { totalMin = it }, Modifier.fillMaxWidth(), "分钟")
            NumberField("每天最多开几局（1–10，答错 / 过期也算）", attempts, { attempts = it }, Modifier.fillMaxWidth(), "局")
            Hint("换来的是今天的娱乐分钟，一小时内有效，用完照旧拦，也不超过每天总上限。")
            Hint("4 道单选，对 3 道才过；每天有局数，答错要歇 3 分钟。")

            Button(
                onClick = {
                    val wanted = TrainingPolicy(
                        enabled = enabled,
                        packages = selected,
                        minutesPerPass = (minutes.toIntOrNull() ?: desired.minutesPerPass).coerceIn(1, 15),
                        dailyMaxPasses = (passes.toIntOrNull() ?: desired.dailyMaxPasses).coerceIn(0, 6),
                        dailyMaxMinutes = (totalMin.toIntOrNull() ?: desired.dailyMaxMinutes).coerceIn(0, 60),
                        attemptsPerDay = (attempts.toIntOrNull() ?: desired.attemptsPerDay).coerceIn(1, 10),
                    )
                    val out = Policy.setTraining(config, wanted, System.currentTimeMillis())
                    store.updateConfig { out.config }
                    message = out.message
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            if (pending.isNotEmpty()) {
                SectionLabel("排队中的改动（放宽 24 小时后生效，期间可以撤回）")
                pending.sortedBy { it.effectiveAt }.forEach { p ->
                    Panel {
                        Text(p.summary, style = MaterialTheme.typography.bodyLarge)
                        KeyValue("提交于", clock(p.createdAt))
                        KeyValue("生效", clock(p.effectiveAt) + "（还有 " + span(p.effectiveAt - now) + "）", LocalSignals.current.warn)
                        OutlinedButton(onClick = { store.updateConfig { Policy.cancel(it, p.id) } }) { Text("撤回") }
                    }
                }
            }

            SectionLabel("现在的规矩")
            Hint("只在「今天挣来的娱乐用完了」时能换；关卡、欠着的事、专注锁、墨灵约的严管、每天总上限都不能用训练抵。")
            Hint("换来的是今天的娱乐分钟（一小时内有效，用完照旧拦），不超过每天总上限。4 道题对 3 道才过；每天有局数，答错歇 3 分钟。")
        }
    }
    OutcomeDialog(message) { message = null }
}

/** 应用名；没装 / 取不到就用包名。 */
internal fun appLabel(pm: PackageManager, pkg: String): String = try {
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}
