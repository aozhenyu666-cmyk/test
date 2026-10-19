package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.agent.ChatThread
import com.zongkong.core.agent.ChatThreads
import java.time.Instant
import java.time.LocalDate

/**
 * 多段对话。
 *
 * 以前只有一条无限堆下去的对话，模型每轮都被旧内容塞满；现在按段分开：
 * 一段说满就收尾，把结论压成交接摘要（carry）带进下一段。每天新开几段有上限（config.chat.threadsPerDay，
 * 默认 1 段/天，0 = 不限），逼着把旧的压完再用，而不是一直开新的把上下文铺满。
 */
@Composable
fun ChatThreadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val chat = context.zk.chat
    val sig = LocalSignals.current
    val log by chat.log.collectAsStateWithLifecycle()
    val threads = ChatThreads.recentFirst(log.threads)
    val perDay = chat.threadsPerDay()
    val opened = chat.openedToday()
    val remaining = ChatThreads.remaining(perDay, opened)
    val limited = remaining == 0
    var notice by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ChatThread?>(null) }

    fun openNew(closeFirst: Boolean) {
        val (ok, msg) = if (closeFirst) chat.closeAndNew() else chat.newThread()
        notice = msg
        if (ok) nav.popBackStack()
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("对话", { nav.popBackStack() }) {
            TextButton(
                onClick = { openNew(true) },
                enabled = !limited,
                modifier = Modifier.testTag("chat-thread-new"),
            ) { Text("关闭并新建", color = if (limited) sig.muted else sig.ai) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenTitle(
                    "多段对话",
                    "上下文不再一条线堆到底：一段说满就收尾，把结论压成交接摘要带进下一段。旧的段还能翻回来看、接着聊。",
                    Modifier.enter(0),
                )
            }
            item {
                Panel(accent = sig.ai, modifier = Modifier.enter(1)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("今天", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                            Text(
                                if (remaining < 0) "不限新建段数" else "还能新开 $remaining 段（每天最多 $perDay 段）",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        Pill(if (remaining < 0) "不限" else "剩 $remaining", if (limited) sig.warn else sig.ai)
                    }
                    Hint(ChatThreads.limitText(perDay, opened))
                    if (notice.isNotBlank()) {
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = if (limited) sig.warn else sig.strict)
                    }
                    BigButton(
                        if (threads.isEmpty()) "开第一段" else "新开一段",
                        { openNew(false) },
                        Modifier.testTag("chat-thread-open-new"),
                        enabled = !limited,
                    )
                    if (threads.isNotEmpty()) {
                        TextButton(onClick = { openNew(true) }, enabled = !limited) {
                            Text("关闭当前段并新建（把结论带过去）", color = if (limited) sig.muted else sig.ai)
                        }
                        Hint("收尾时会用一次便宜的 AI 调用把这段压成 120 字以内的交接；没接 AI 或失败就用本地摘要，不影响用。")
                    }
                }
            }
            if (threads.isEmpty()) {
                item {
                    Panel(Modifier.enter(2)) {
                        Text("还没有对话", style = MaterialTheme.typography.titleSmall)
                        Hint("回到对话页说一句，就会自动建第一段。之后说满一段会自动收尾换下一段，今天的额度用完就压着旧的接着用。")
                        TextButton(onClick = { nav.popBackStack() }) { Text("回对话页", color = sig.ai) }
                    }
                }
            } else {
                items(threads, key = { it.id }) { t ->
                    ThreadCard(
                        t = t,
                        active = t.id == log.activeThread?.id,
                        onOpen = {
                            chat.openThread(t.id)
                            nav.popBackStack()
                        },
                        onClose = { chat.closeThread(t.id) },
                        onDelete = { pendingDelete = t },
                    )
                }
            }
            item {
                Hint(
                    "只有当前这一段的对话会被带进模型的上下文；更早的内容已经压成交接摘要。\n" +
                        "删掉一段就真的没了；只是不想让它继续堆，用“收尾”就够了。",
                    Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }

    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这一段？") },
            text = {
                Text(
                    "「${t.title.ifBlank { ChatThreads.titleOf(t) }}」的 ${t.count} 条消息和交接摘要都会没，删了回不来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chat.deleteThread(t.id)
                    pendingDelete = null
                }) { Text("删掉", color = sig.strict) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("算了") } },
        )
    }
}

@Composable
private fun ThreadCard(
    t: ChatThread,
    active: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    val sig = LocalSignals.current
    Panel(accent = if (active) sig.ai else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    t.title.ifBlank { ChatThreads.titleOf(t) },
                    style = MaterialTheme.typography.titleMedium.merge(SerifTitle),
                )
                Text(
                    buildString {
                        append(threadClock(if (t.updatedAt > 0) t.updatedAt else t.createdAt))
                        append(" · ").append(t.count).append(" 条")
                        when {
                            active -> append(" · 正在用")
                            t.closed -> append(" · 已收尾")
                            else -> append(" · 还没收尾")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) sig.ai else sig.muted,
                )
            }
            if (active) Pill("当前", sig.ai)
        }
        if (t.carry.isNotBlank()) Hint("交接：" + oneLine(t.carry, 90))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!active) TextButton(onClick = onOpen) { Text("打开", color = sig.ai) }
            if (!t.closed) TextButton(onClick = onClose) { Text("收尾", color = sig.muted) }
            TextButton(onClick = onDelete) { Text("删除", color = sig.strict) }
        }
    }
}

/** 时间：今天只显示几点几分，昨天写“昨天”，更早写月/日。 */
private fun threadClock(at: Long): String {
    if (at <= 0) return "时间不明"
    val zone = com.zongkong.core.DayClock.BEIJING
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    return when (t.toLocalDate()) {
        today -> "%02d:%02d".format(t.hour, t.minute)
        today.minusDays(1) -> "昨天 %02d:%02d".format(t.hour, t.minute)
        else -> "%d/%d %02d:%02d".format(t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }
}

private fun oneLine(text: String, max: Int): String {
    val t = text.replace(Regex("\\s+"), " ").trim()
    return if (t.length <= max) t else t.take(max - 1) + "…"
}
