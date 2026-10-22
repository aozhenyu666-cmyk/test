package com.zongkong.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.data.Actions
import com.zongkong.app.zk
import com.zongkong.core.GatePhase
import com.zongkong.core.plan.QuickPlan
import java.time.Instant

/**
 * 对话顶上的“今天”（0.14）：不用离开对话就知道现在的状况——
 *   严管还是放行、娱乐用了多少；现在 / 下一段做什么；眼睛掉没掉；今天还差哪几步（点一下就在对话里做）。
 * 点状态那一行去“今天”页看全部。
 */
@Composable
fun ChatTodayBar(nav: NavHostController, say: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val sig = LocalSignals.current
    val work by store.work.collectAsStateWithLifecycle()
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    // 交了关卡（今天的记录变了）马上刷新，不等 30 秒
    val status = remember(config, day, work, now) { store.status(System.currentTimeMillis()) }
    val zone = store.zone
    val missing = remember(now / 60_000) { PermissionStatus.missingCritical(context) }
    val slot = remember(work, now) {
        QuickPlan.today(work, now, zone).firstOrNull { (a, t) -> a.actionStatus.open && t.second > now }
    }
    fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp).testTag("chat-today"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { nav.navigate("home") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                status.headline + if (status.quotaMin > 0) " · 娱乐 ${status.playMin}/${status.quotaMin}" else "",
                statusColor(status), dot = status.strict,
            )
            Text(
                slot?.let { (a, t) ->
                    val (s, e) = t
                    if (now >= s) "现在：${a.title} · 还剩 ${(e - now) / 60_000 + 1} 分" else "${hm(s)} ${a.title}" + if (s - now < 3_600_000) " · ${(s - now) / 60_000 + 1} 分钟后" else ""
                } ?: "今天没有排着的事",
                style = MaterialTheme.typography.labelLarge, color = if (slot == null) sig.muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        if (missing.isNotEmpty()) {
            Text(
                "没开：" + missing.joinToString("、") + " · 点这里去开",
                style = MaterialTheme.typography.labelMedium, color = sig.strict, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { nav.navigate("settings/perm") }.testTag("chat-perm"),
            )
        }
        // 今天还差的几步：点一下就在对话里做
        val todo = status.gates.filter { !it.done && it.phase != GatePhase.NOT_OPEN }
        if (todo.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("还差", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                todo.forEach { g ->
                    QuickChip("${g.gate.title}（${com.zongkong.core.DayClock.hhmm(g.gate.deadline)}前）", if (g.phase == GatePhase.OVERDUE) sig.strict else sig.plan) {
                        when (g.gate.id) {
                            Actions.PLAN_GATE -> say("帮我做今日部署：把今天排一下")
                            "hq_review" -> say("今天收尾，帮我做日终验收")
                            "think_one" -> say("我想推进一个判断：")
                            else -> nav.navigate("gate/${g.gate.id}")
                        }
                    }
                }
            }
        }
    }
}

/** 输入框上面的几个常用说法（空着的时候才出现）。 */
@Composable
fun ChatStarters(send: (String) -> Unit, fill: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("chat-starters"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sig = LocalSignals.current
        QuickChip("排今天", sig.plan) { send("帮我把今天排一下") }
        QuickChip("我现在要做…", sig.plan) { fill("我现在要做") }
        QuickChip("做完了", sig.free) { send("我做完了一步，要交证据") }
        QuickChip("往后挪", sig.warn) { send("有件事今天做不了，帮我改个时间") }
        QuickChip("现在做什么", sig.ai) { send("现在该做什么？") }
        QuickChip("今天收尾", sig.think) { send("今天收尾，帮我做日终验收") }
    }
}
