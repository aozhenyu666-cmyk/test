package com.zongkong.app.ui

import android.app.TimePickerDialog
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.link.MolingBridge
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.moling.Grant
import com.zongkong.core.moling.GrantChange
import com.zongkong.core.moling.GrantKind
import com.zongkong.core.moling.Grants
import com.zongkong.core.moling.Holds
import com.zongkong.core.moling.MolingOrder
import com.zongkong.core.moling.OrderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 只在可见时跳动，离开页面 / 锁屏即停；回来立即取当前时刻。 */
@Composable
private fun molingNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    return now
}

@Composable
fun GrantsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val book by store.moling.collectAsStateWithLifecycle()
    val now = molingNow()
    val grants = Grants.applyDue(book.grants, now)
    fun save(kind: GrantKind, next: Grant?) {
        runCatching {
            var message = ""
            store.updateMoling { b ->
                val (g, msg) = Grants.set(b.grants, kind, next, System.currentTimeMillis())
                message = msg
                b.copy(grants = g)
            }
            message
        }.onSuccess { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            .onFailure {
                Log.w("MolingScreens", "保存授权书失败", it)
                Toast.makeText(context, "没保存成，请重试", Toast.LENGTH_LONG).show()
            }
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("墨灵的授权书", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { nav.navigate("moling/orders") }) { Text("墨灵的建议") }
            Hint("授权书里的事，墨灵可以不问你、直接让总控做：总控先通知你“60 秒后执行”，你可以取消。\n给得更多立即生效；收回或缩小是放宽，24 小时后才生效。紧急放行不受影响。\n拦截名单的改动和任何放宽，永远不能授权。")
            Holds.strict(book.holds, now)?.let { Text("临时严管到 ${clock(it.until, store.zone)}") }
            val cut = Holds.cutToday(book.holds, now, store.zone)
            if (cut > 0) Text("今天减了 $cut 分钟娱乐")
            GrantKind.entries.forEach { kind ->
                GrantEditor(
                    kind, grants.grants.firstOrNull { it.kind == kind },
                    grants.pending.firstOrNull { it.kind == kind }, store.zone,
                    onSave = { save(kind, it) },
                    onRevokeChange = { save(kind, grants.grants.firstOrNull { it.kind == kind }) },
                )
            }
            val day = DayClock.date(now, store.zone)
            val today = book.orders.filter {
                it.grant.isNotBlank() && DayClock.date(it.decidedAt.takeIf { t -> t > 0 } ?: it.receivedAt, store.zone) == day
            }
            Text("今天按授权执行了 ${today.count { it.state == OrderState.DONE }} 条 · 被你取消了 ${today.count { it.state == OrderState.VETOED }} 条")
        }
    }
}

private fun defaultGrant(kind: GrantKind): Grant = when (kind) {
    GrantKind.FOCUS -> Grant(kind, 45, 3, 480, 1380)
    GrantKind.STRICT -> Grant(kind, 120, 1, 480, 1410)
    GrantKind.CUT_PLAY -> Grant(kind, 30, 1, 480, 1380)
    GrantKind.HQ -> Grant(kind, 30, 1, 480, 1320)
    GrantKind.PLAN, GrantKind.COMMIT -> Grant(kind, perDay = 10, fromMin = 0, toMin = 0)
}

@Composable
private fun GrantEditor(
    kind: GrantKind, active: Grant?, pending: GrantChange?, zone: ZoneId,
    onSave: (Grant?) -> Unit, onRevokeChange: () -> Unit,
) {
    val context = LocalContext.current
    // 草稿只在保存时交核心；不会每拨一次开关就排一个冷静期。
    val initial = pending?.next ?: active ?: defaultGrant(kind)
    var enabled by rememberSaveable(kind, active, pending) { mutableStateOf(if (pending != null) pending.next != null else active != null) }
    var minutes by rememberSaveable(kind, active, pending) { mutableStateOf(initial.maxMinutes) }
    var count by rememberSaveable(kind, active, pending) { mutableStateOf(initial.perDay) }
    var from by rememberSaveable(kind, active, pending) { mutableStateOf(initial.fromMin) }
    var to by rememberSaveable(kind, active, pending) { mutableStateOf(initial.toMin) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        if (enabled) {
            if (kind != GrantKind.PLAN && kind != GrantKind.COMMIT) {
                GrantStepper("每次最多 $minutes 分钟", minutes > 5, minutes < 240,
                    { minutes = (minutes - 5).coerceAtLeast(5) }, { minutes = (minutes + 5).coerceAtMost(240) })
            }
            GrantStepper("每天最多 $count 次", count > 1, count < 10, { count-- }, { count++ })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> from = h * 60 + m }, from / 60, from % 60, true).show()
                }) { Text("从 ${DayClock.hhmm(from)}") }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m -> to = h * 60 + m }, to / 60, to % 60, true).show()
                }) { Text("到 ${DayClock.hhmm(to)}") }
            }
            Hint(when { from == to -> "全天"; to < from -> "跨夜，到第二天结束时间"; else -> "只在这个时间段内" })
        }
        Button(onClick = { onSave(if (enabled) Grant(kind, minutes, count, from, to) else null) }) { Text("保存") }
        pending?.let {
            val stamp = Instant.ofEpochMilli(it.effectiveAt).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            Hint("收回 / 缩小在冷静期，$stamp 生效")
            TextButton(onClick = onRevokeChange) { Text("撤回") }
        }
    }
}

@Composable
private fun GrantStepper(text: String, canLess: Boolean, canMore: Boolean, less: () -> Unit, more: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        TextButton(onClick = less, enabled = canLess) { Text("−") }
        TextButton(onClick = more, enabled = canMore) { Text("＋") }
    }
}

@Composable
fun MolingOrdersBanner(nav: NavHostController) {
    val context = LocalContext.current
    val book by context.zk.store.moling.collectAsStateWithLifecycle()
    val count = book.orders.count { it.state == OrderState.PENDING || it.state == OrderState.SCHEDULED }
    if (count > 0) Panel(onClick = { nav.navigate("moling/orders") }) {
        Text("墨灵有 $count 条建议等你", style = MaterialTheme.typography.titleSmall)
        Hint("查看、执行，或取消授权内的倒计时。")
    }
}

@Composable
fun MolingOrdersScreen(nav: NavHostController) {
    val context = LocalContext.current
    val book by context.zk.store.moling.collectAsStateWithLifecycle()
    val now = molingNow()
    Column(Modifier.fillMaxSize()) {
        BackBar("墨灵的建议", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf("等你确认" to OrderState.PENDING, "马上执行" to OrderState.SCHEDULED).forEach { (label, state) ->
                SectionLabel(label)
                val orders = book.orders.filter { it.state == state }.sortedByDescending { it.receivedAt }
                if (orders.isEmpty()) Hint("没有")
                orders.forEach { order -> key(order.id) { MolingOrderCard(order, now) } }
            }
            SectionLabel("最近的")
            val recent = book.orders.filter { it.state != OrderState.PENDING && it.state != OrderState.SCHEDULED }
                .sortedByDescending { it.decidedAt.takeIf { t -> t > 0 } ?: it.receivedAt }.take(20)
            if (recent.isEmpty()) Hint("还没有处理过的建议")
            recent.forEach { order -> key(order.id) { MolingOrderCard(order, now) } }
        }
    }
}

@Composable
private fun MolingOrderCard(order: MolingOrder, now: Long) {
    val context = LocalContext.current
    // 执行域跟着 Application，离开这一页不会取消已经同意执行的事。
    var busy by remember(order.id) { mutableStateOf(false) }
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        context.zk.appScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("MolingScreens", "处理墨灵建议失败", e)
                Toast.makeText(context, "这条没处理成，请重试", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    Panel {
        Text(order.text, style = MaterialTheme.typography.bodyLarge)
        Hint("收到于 ${clock(order.receivedAt, context.zk.store.zone)} · ${order.state.label}")
        if (order.result.isNotBlank()) Text(order.result)
        when (order.state) {
            OrderState.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act { MolingBridge.run(context, order.id) } }, enabled = !busy) { Text(if (busy) "在做…" else "执行") }
                TextButton(onClick = { act { MolingBridge.decline(context, order.id) } }, enabled = !busy) { Text("不用") }
            }
            OrderState.SCHEDULED -> {
                val seconds = ((order.runAt - now + 999) / 1_000).coerceAtLeast(0)
                Text(if (seconds > 0) "$seconds 秒后执行" else "即将执行")
                TextButton(onClick = { act { MolingBridge.veto(context, order.id) } }, enabled = !busy) { Text("取消") }
            }
            else -> Unit
        }
    }
}
