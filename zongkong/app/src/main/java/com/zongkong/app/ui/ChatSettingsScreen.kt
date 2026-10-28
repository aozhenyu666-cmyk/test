package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.agent.ChatMode

/**
 * 对话段数设置。
 *
 * 用户的痛点：“它总共应该可以开，比如开两到三个对话吧，光一个对话不太行。”
 * 以前这条只能改代码里的默认值（还是 1 段/天），界面上根本没地方调；这里让它可在 [不限 / 1–5] 之间选。
 * 额度是「当下」和「整体」两条线**各算各的**：上限到了不是不能聊，而是把旧的一段收尾、
 * 带着交接摘要接着用——这正是分段存在的意义，不能因为放宽了上限就把上下文重新堆回去。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val chat = context.zk.chat
    val config by store.config.collectAsStateWithLifecycle()
    // 今天用掉几段 = 记账数 + 段数兜底，跟着 chat.json 变：换模式、开新段后这里要立刻对上
    val log by chat.log.collectAsStateWithLifecycle()
    val mode by chat.mode.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val perDay = config.chat.threadsPerDay

    fun save(v: Int) {
        // 一起置 threadsMigrated：这个值是用户自己选的，以后读配置不能再被“旧默认 1 → 3”的迁移改掉
        store.updateConfig { it.copy(chat = it.chat.copy(threadsPerDay = v, threadsMigrated = true)) }
    }

    // 两条线各用掉多少：数字来自额度记账（删过段也不还），段数是这条线现在一共有几段
    val usage = remember(log, mode) {
        ChatMode.entries.map { m ->
            Usage(m, chat.openedToday(m), chat.threadsOf(m).size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("对话段数", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenTitle(
                "每天最多新开几段",
                "一段说满就收尾，把结论压成交接摘要带进下一段。这里管的是「一天能开几段」，不是「一共能有几段」。",
                Modifier.enter(0),
            )

            Panel(accent = sig.ai, modifier = Modifier.enter(1)) {
                Text(
                    if (perDay <= 0) "现在：不限" else "现在：每天最多 $perDay 段",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 0 就是“不限”：放第一个，因为不想被拦的人最先看到
                    listOf(0, 1, 2, 3, 4, 5).forEach { v ->
                        FilterChip(
                            selected = perDay == v,
                            onClick = { save(v) },
                            label = { Text(if (v == 0) "不限" else "$v 段") },
                        )
                    }
                }
                Hint("改完立刻生效，今天已经用掉的额度不会退回来；明天按新的算。")
            }

            SectionLabel("两条线各算各的")
            Panel(modifier = Modifier.enter(2)) {
                usage.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(u.mode.label + "对话", style = MaterialTheme.typography.titleSmall)
                            Hint(
                                when {
                                    u.openedToday > 0 || u.total > 0 -> "今天新开 ${u.openedToday} 段 · 这条线一共 ${u.total} 段"
                                    else -> "今天还没开过；一共 ${u.total} 段"
                                },
                            )
                        }
                        // 正在用的那条线标一下：用户切过模式以后，一眼就知道哪个数字是自己眼前这段
                        if (u.mode == mode) Tag("正在用", sig.ai) else Tag(u.mode.label, sig.muted)
                    }
                }
                Hint(
                    "当下和整体各自有一份额度：在整体里开新段，不会把当下的额度用掉。\n" +
                        "额度到了也不影响聊天——只是不能在**这条线**上新开，把旧的一段收尾、带着交接摘要接着用就行。",
                )
            }

            SectionLabel("为什么要有上限")
            Panel(modifier = Modifier.enter(3)) {
                Hint(
                    "不限新建的话，聊不顺就开一段新的，旧内容全丢、模型每轮又被塞满，于是又回到“上下文很繁琐”。\n" +
                        "上限逼着把一段用到底、收尾时压成 120 字以内的交接：结论带走，细节丢掉。",
                )
            }
            Hint("多段对话的列表在总控对话页 →「对话」，那里能翻旧的段、收尾、删除。", Modifier.padding(bottom = 16.dp))
        }
    }
}

/** 一条线今天用掉多少、一共有几段（纯展示用）。 */
private data class Usage(val mode: ChatMode, val openedToday: Int, val total: Int)
