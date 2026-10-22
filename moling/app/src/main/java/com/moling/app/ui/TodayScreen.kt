package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import com.moling.app.BrainHost
import com.moling.core.link.describe
import com.moling.app.ui.art.InkBubbles
import com.moling.app.ui.art.InkClouds
import com.moling.app.ui.art.InkFlame
import com.moling.app.ui.art.InkLeaf
import com.moling.app.ui.art.InkMoon
import com.moling.app.ui.art.InkMountain
import com.moling.app.ui.art.InkRipple
import com.moling.app.ui.art.InkSteps
import com.moling.app.ui.art.InkThinking
import com.moling.app.ui.art.AnimatedCount
import com.moling.app.ui.art.InkTitle
import com.moling.app.ui.art.inkEnter
import com.moling.app.ui.art.rememberInkProgress
import com.moling.app.ui.art.InkWaves
import com.moling.app.ui.art.WashOrange
import com.moling.app.ui.art.moonPhase
import com.moling.core.flow.ChannelKind
import com.moling.core.Brain
import com.moling.core.Notice
import com.moling.core.Proposal
import com.moling.core.ProposalState
import com.moling.core.flow.Cause
import com.moling.core.flow.Channel
import com.moling.core.flow.ChannelState
import com.moling.core.flow.Flow
import com.moling.core.mind.CHOICE_GO
import com.moling.core.mind.Card as MindCard
import com.moling.core.mind.Choice
import com.moling.core.mind.Opening
import com.moling.core.mind.Reply
import com.moling.core.predict.Predict
import com.moling.core.predict.Prediction
import com.moling.core.usage.UsageDay
import com.moling.core.usage.UsageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 今天页独用的协程域：切到别的标签也不打断正在排的水道，也不让提议执行到一半被取消。 */
private val todayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 今天页，从上到下：提醒条、此刻（导演卡 + 接力棒）、今天的水道、改道、等你决定的、今天的预言、最近做了什么。
 *
 * 界面不自己算：状态全从 [BrainHost] 取，提议用户点“好”之后才交给大脑。
 * 排水道、定稿、改道、回应导演卡都要调模型或总控，所以都放在 [todayScope] 里跑，
 * 每一步都用 runCatching 兜底：总控连不上、模型失败时界面不卡，墨灵也不崩。
 * 卡片是共用组件 ui/ProposalCard.kt（同一个包，不用 import）。
 */
@Composable
fun TodayScreen(onReady: () -> Unit = {}) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by BrainHost.state.collectAsState()
    val notices by BrainHost.notices.collectAsState()
    // 计划代驾：草稿、本地采纳、到点提醒时间（和总控回执分开显示）。
    val planState by BrainHost.plan.collectAsState()

    // 每 20 秒走一次表：大字时间和“当前这一条”的高亮不用等别处刷新。
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(20_000L)
        }
    }
    val now = clock

    // 水道按日历日（0 点换日）算，和导演的 4 点换日不同。
    val today = Brain.calendarDay(now)
    val flow = state.flow?.takeIf { it.day == today }

    var planning by remember { mutableStateOf(false) }
    var confirmProposal by remember { mutableStateOf<Proposal?>(null) }
    var opening by remember { mutableStateOf<Opening?>(null) }

    // M15：导演此刻要说的卡（心跳推来的），以及「有结论」的输入框。
    val pushed by BrainHost.directorCard.collectAsState()
    var card by remember { mutableStateOf<MindCard?>(BrainHost.currentCard(now)) }
    var conclusionCard by remember { mutableStateOf<MindCard?>(null) }
    var conclusionText by remember { mutableStateOf("") }
    LaunchedEffect(pushed) { if (pushed != null) card = pushed }
    LaunchedEffect(Unit) { if (card == null) card = BrainHost.currentCard(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { BrainHost.refreshUsage(force = true) }

    fun toast(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    /** 把一次回应交给导演：下一张卡替换当前卡，null 就收起；失败只 Toast，卡片不动。 */
    fun sendReply(reply: Reply, after: ((MindCard?) -> Unit)? = null) {
        todayScope.launch {
            val result = runCatching { BrainHost.reply(reply) }
            if (result.isFailure) {
                toast("刚才没接上")
                return@launch
            }
            val next = result.getOrNull()
            card = next
            // 让心跳推来的卡也跟着走：换页 / 重开回今天页时，不会又弹出上一张。
            BrainHost.setDirectorCard(next)
            after?.invoke(next)
        }
    }

    /** 划掉这张卡：先收起，再把“被划掉”告诉导演。 */
    fun dismissCard(c: MindCard) {
        card = null
        todayScope.launch {
            runCatching { BrainHost.reply(Reply.Dismissed(c.id, System.currentTimeMillis())) }
            BrainHost.setDirectorCard(null)
        }
    }

    /** 点了一个选项；CHOICE_GO 是“去聊”：先回应，再让导演准备开场白。 */
    fun pick(c: MindCard, choice: Choice) {
        val at = System.currentTimeMillis()
        if (choice.key != CHOICE_GO) {
            sendReply(Reply.Pick(c.id, at, choice.key))
            return
        }
        sendReply(Reply.Pick(c.id, at, choice.key), after = { next ->
            todayScope.launch {
                // 回应后导演通常收起这张卡（next 为空），那就用这张卡的演员和 id 要开场白。
                val op = runCatching { BrainHost.opening(next?.actor ?: c.actor, (next ?: c).id) }.getOrNull()
                if (op != null) opening = op
            }
        })
    }

    fun acceptProposal(p: Proposal) {
        todayScope.launch {
            val out = runCatching { BrainHost.accept(p.id, System.currentTimeMillis()) }.getOrNull()
            if (out == null) {
                toast("刚才没办成，等会儿再试")
                return@launch
            }
            toast(out.message)
            out.opening?.let { opening = it }
        }
    }

    fun declineProposal(p: Proposal) {
        todayScope.launch {
            val err = runCatching { BrainHost.decline(p.id, System.currentTimeMillis()) }.exceptionOrNull()
            toast(if (err == null) "好，这条不用了" else "刚才没办成，等会儿再试")
        }
    }

    fun retryProposal(p: Proposal) {
        todayScope.launch {
            val out = runCatching { BrainHost.retry(p.id, System.currentTimeMillis()) }.getOrNull()
            toast(out?.message ?: "刚才没办成，等会儿再试")
        }
    }

    fun acceptNotice(notice: Notice) {
        todayScope.launch {
            val out = runCatching { BrainHost.accept(notice.proposalId, System.currentTimeMillis()) }.getOrNull()
            if (out == null) {
                toast("刚才没办成，等会儿再试")
                return@launch
            }
            toast(out.message)
            out.opening?.let { opening = it }
            // 处理过的提议不再挂在提醒条上。
            runCatching { BrainHost.dismissNotice(notice.at) }
        }
    }

    fun declineNotice(notice: Notice) {
        todayScope.launch {
            runCatching { BrainHost.decline(notice.proposalId, System.currentTimeMillis()) }
            runCatching { BrainHost.dismissNotice(notice.at) }
            toast("好，这条不用了")
        }
    }

    fun planDay(force: Boolean) {
        if (planning) return
        todayScope.launch {
            planning = true
            try {
                val err = runCatching { BrainHost.planDay(System.currentTimeMillis(), force = force) }.exceptionOrNull()
                if (err != null) toast("没排成，等会儿再试")
            } finally {
                planning = false
            }
        }
    }

    fun confirmFlow() {
        todayScope.launch {
            val p = runCatching { BrainHost.confirmFlow(System.currentTimeMillis()) }.getOrNull()
            if (p == null) toast("现在还不能交给总控") else confirmProposal = p
        }
    }

    fun reroute(cause: Cause) {
        todayScope.launch {
            val msg = runCatching { BrainHost.reroute(cause, System.currentTimeMillis()) }
                .getOrElse { "刚才没办成，等会儿再试" }
            toast(if (msg.isBlank()) "现在没有在流的水道" else msg)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.lastStatus?.let { status ->
            val parts = buildList {
                if (status.holdUntil > now) add("严管到 ${Flow.fmt(Flow.minuteOf(status.holdUntil))}")
                if (status.cutToday > 0) add("今天娱乐 −${status.cutToday} 分钟")
                if (status.openCommitments > 0) add("还有 ${status.openCommitments} 条约定")
            }
            if (parts.isNotEmpty()) Text(
                parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 0. 还没准备好：一张小卡，点进准备清单
        val notReady = remember(state.lastStatus) { readyItems(context, {}, {}).count { it.essential && !it.ok } }
        if (notReady > 0) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(0.7.dp, Seal.copy(alpha = 0.35f)),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { onReady() },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    InkRipple(Modifier.size(40.dp), color = Seal)
                    Spacer(Modifier.width(10.dp))
                    Text("还差 $notReady 步，她才能完整地活。点这里一项项打开。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }

        // 1. 提醒条：最多 3 条，新的在前；挂着提议的带“好 / 不用”。
        notices.take(3).forEach { notice ->
            NoticeBar(
                notice = notice,
                onDismiss = { runCatching { BrainHost.dismissNotice(notice.at) } },
                onAccept = if (notice.proposalId.isNotBlank()) ({ acceptNotice(notice) }) else null,
                onDecline = if (notice.proposalId.isNotBlank()) ({ declineNotice(notice) }) else null,
            )
        }

        // 1.5 此刻：导演要说的一张卡 + 没想完的接力棒（M15）。
        val mindCard = card
        if (mindCard != null) {
            DirectorCardView(
                card = mindCard,
                onDismiss = { dismissCard(mindCard) },
                onReply = { sendReply(it) },
                onPick = { pick(mindCard, it) },
                onConclusion = {
                    conclusionText = ""
                    conclusionCard = mindCard
                },
                onCopy = { line ->
                    clipboard.setText(AnnotatedString(line))
                    toast("复制了")
                },
            )
        }
        val baton = BrainHost.directorBaton()
        baton?.let { b ->
            Text(
                "没想完的：${b.question} → 下一问：${b.nextAsk.ifBlank { "—" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 2. 今天的水道：月相 = 一天走到哪；下面的水纹 = 今天的水在流
        Box(Modifier.fillMaxWidth()) {
            InkWaves(Modifier.fillMaxWidth().height(86.dp).align(Alignment.BottomCenter), lines = 14)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 26.dp)) {
                InkMoon(moonPhase(Flow.minuteOf(now)), Modifier.size(64.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Flow.fmt(Flow.minuteOf(now)),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        flow?.intent ?: "今天还没排",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 2.1 精力：总控记的做事 / 娱乐；偏离的升级走到第几级
        state.lastStatus?.let { z ->
            val total = (z.workMinutesToday + z.playMinutesToday).coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkFlame(z.workMinutesToday / total.toFloat(), Modifier.size(40.dp), animated = z.workMinutesToday > 0)
                Spacer(Modifier.width(8.dp))
                Text(
                    "做事 ${z.workMinutesToday} 分钟 · 娱乐 ${z.playMinutesToday} 分钟" +
                        if (z.mainTitle.isNotBlank()) " · 主线「${z.mainTitle}」${if (z.mainDone) "做完了" else if (z.mainStarted) "在做" else "没开始"}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val ladder = state.ladder
                if (ladder != null && !ladder.answered && ladder.idleSince == 0L) {
                    InkSteps(ladder.rung.ordinal + 1, com.moling.core.watch.Rung.entries.size, Modifier.size(36.dp))
                    Text(ladder.rung.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            // 以思代锁（第十五批 A3）：总控开了训练兑换才出现；娱乐分钟变了就重新问一次
            TrainingLine(refreshKey = z.playMinutesToday)
        }

        if (flow == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkMountain(Modifier.size(88.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "排一条，水才知道往哪流。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (planning) InkThinking("她在排…")
                    else Button(onClick = { planDay(false) }) { Text("排今天的水道") }
                }
            }
        } else {
            // 当前这一条：正在窗口里的那条；没有就是下一条。
            val currentId = Flow.current(flow, Flow.minuteOf(now))?.id
            flow.channels.filter { it.startMin >= 0 }.sortedBy { it.startMin }.forEach { channel ->
                ChannelRow(channel = channel, current = channel.id == currentId)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { planDay(true) }, enabled = !planning) {
                    Text(if (planning) "在排…" else "重排")
                }
                Button(onClick = { confirmFlow() }, enabled = !flow.confirmed && !planning) {
                    Text(if (flow.confirmed) "已定稿" else "定稿，交给总控")
                }
            }
            val shown = confirmProposal
            if (shown != null) {
                // 卡片跟着大脑状态走：点“好”之后显示“交给总控了 / 没送到”，不用自己改这张卡。
                val live = state.proposals.firstOrNull { it.id == shown.id } ?: shown
                ProposalCard(
                    proposal = live,
                    onAccept = { acceptProposal(it) },
                    onDecline = { declineProposal(it) },
                    onRetry = { retryProposal(it) },
                )
            }
        }

        // 2.5 计划代驾（第十三批 G1）：草稿给“采纳 / 不采纳”；采纳只在本地下，不自动交给总控。
        val todayDraft = planState.drafts.firstOrNull { it.day == today }
        val adoption = planState.adoption?.takeIf { it.day == today }
        if (todayDraft != null || adoption != null) {
            SectionTitle("计划代驾")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (todayDraft != null) "草稿 v" + todayDraft.revision + "：" + todayDraft.intent else "计划代驾",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "本地：" + (adoption?.state?.label ?: "没采纳"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (adoption?.state == com.moling.core.flow.AdoptionState.LOCAL_ADOPTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (flow?.confirmed == true) "定稿：你已定稿" else "定稿：还没定稿",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val waitMin = (BrainHost.planPolicy().remindAfterMs / 60_000L).toInt()
                    when (adoption?.state) {
                        com.moling.core.flow.AdoptionState.DRAFT -> {
                            val left = (adoption?.eligibilityAt ?: 0L) - now
                            val reminded = (adoption?.remindedAt ?: 0L) > 0
                            Text(
                                when {
                                    reminded -> "到点了：点「一键采用」才算你用了这份计划（只是记下你的选择，不会自动执行）。"
                                    left > 0 -> "还没采纳。约 " + ((left / 60_000L) + 1) + " 分钟后提醒你一键采用（到点不会自己用）。"
                                    else -> "还没采纳。点「采纳」只是记下你的选择，不会自动执行。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reminded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        com.moling.core.flow.AdoptionState.LOCAL_ADOPTED -> Text(
                            "你采用了这份计划：只在墨灵里记下了；没有交给总控，也没有执行任何现实动作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        null -> Text(
                            "点「采纳」只是记下你的选择，不会自动执行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            adoption?.message.orEmpty().ifBlank { adoption?.state?.label.orEmpty() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val busy = adoption != null && adoption.state in setOf(
                            com.moling.core.flow.AdoptionState.EXECUTION_REQUESTED,
                            com.moling.core.flow.AdoptionState.ZK_PENDING,
                            com.moling.core.flow.AdoptionState.ACTIVE,
                        )
                        Button(
                            onClick = {
                                todayScope.launch {
                                    val r = runCatching { BrainHost.adoptPlan(now) }.getOrNull()
                                    Toast.makeText(context, r?.message ?: "刚才没接上", Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = todayDraft != null && !busy && adoption?.state != com.moling.core.flow.AdoptionState.LOCAL_ADOPTED,
                        ) {
                            Text(
                                when {
                                    adoption?.state == com.moling.core.flow.AdoptionState.LOCAL_ADOPTED -> "已本地采用"
                                    adoption?.state == com.moling.core.flow.AdoptionState.DRAFT && (adoption?.remindedAt ?: 0L) > 0 -> "一键采用"
                                    else -> "采纳"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                todayScope.launch {
                                    val r = runCatching { BrainHost.cancelPlanAdoption(now) }.getOrNull()
                                    Toast.makeText(context, r?.message ?: "刚才没接上", Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = adoption != null && adoption.state in setOf(
                                com.moling.core.flow.AdoptionState.DRAFT,
                                com.moling.core.flow.AdoptionState.LOCAL_ADOPTED,
                            ),
                        ) { Text("不采纳") }
                    }
                    // G2：已经本地采用 → 他点了才按授权书交给总控；账本里每条的状态照总控回执写
                    if (adoption?.state == com.moling.core.flow.AdoptionState.LOCAL_ADOPTED) {
                        OutlinedButton(onClick = {
                            todayScope.launch {
                                val msg = runCatching { BrainHost.executePlan(System.currentTimeMillis()) }.getOrDefault("刚才没接上")
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }) { Text("交给总控（按授权书）") }
                    }
                    // G5：“本地已采用”和“总控已确认”分两行写，别让人以为采用了就执行了
                    if (adoption != null) {
                        val split = com.moling.core.flow.PlanChain.split(adoption, planState.operations)
                        Text(split.local, style = MaterialTheme.typography.bodySmall)
                        Text(split.zongkong, style = MaterialTheme.typography.bodySmall)
                    }
                    val ops = planState.operations.filter { it.planId == todayDraft?.planId }.takeLast(6)
                    if (ops.isNotEmpty()) {
                        Text("交给总控的：", style = MaterialTheme.typography.labelMedium)
                        ops.forEach { op ->
                            Text(
                                "· " + op.command.describe().substringBefore("。原因").take(28) + " — " + op.state.label +
                                    if (op.message.isNotBlank()) "（" + op.message.take(24) + "）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 3. 改道：用户自己点的按钮，直接做。
        SectionTitle("改道")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Cause.entries.forEach { cause ->
                TextButton(onClick = { reroute(cause) }, modifier = Modifier.weight(1f)) {
                    Text(cause.label)
                }
            }
        }

        // 4. 等你决定的：定稿的那张已经显示在“定稿”按钮下面了，这里不再重复。
        val pending = BrainHost.pending(now).filter { it.id != confirmProposal?.id }
        if (pending.isNotEmpty()) {
            SectionTitle("等你决定的")
            pending.forEach { proposal ->
                ProposalCard(
                    proposal = proposal,
                    onAccept = { acceptProposal(it) },
                    onDecline = { declineProposal(it) },
                    onRetry = { retryProposal(it) },
                )
            }
        }

        // 大脑在后台直接交的 SENT 不属于 pending，单独保留回执和结局。
        val sent = state.proposals.filter {
            it.state == ProposalState.SENT && Brain.calendarDay(it.createdAt) == today && it.id != confirmProposal?.id
        }.takeLast(20).asReversed()
        if (sent.isNotEmpty()) {
            SectionTitle("交给总控的")
            sent.forEach { proposal ->
                ProposalCard(proposal, { acceptProposal(it) }, { declineProposal(it) }, { retryProposal(it) })
            }
        }

        // 4.4 思维转速（E3）：最近 3 小时动了多少脑；低了就邀一局。
        val rpmNow = System.currentTimeMillis()
        val rpmScore = com.moling.core.spar.Rpm.score(state.mindMarks, rpmNow)
        SectionTitle("思维转速")
        Row(verticalAlignment = Alignment.CenterVertically) {
            val rpmProgress = rememberInkProgress(duration = 900)
            Box(contentAlignment = Alignment.Center) {
                com.moling.app.ui.art.InkRing(
                    ((rpmScore / 12f).coerceIn(0f, 1f)) * rpmProgress.value,
                    Modifier.size(64.dp),
                    color = com.moling.app.ui.art.WashViolet,
                )
                AnimatedCount(rpmScore, MaterialTheme.typography.titleMedium, Ink)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    com.moling.core.spar.Rpm.line(state.mindMarks, rpmNow),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rpmScore < com.moling.core.spar.Rpm.LOW) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("脑子歇太久了，来一局？", style = MaterialTheme.typography.labelSmall, color = Seal)
                        TextButton(onClick = {
                            todayScope.launch {
                                val turn = runCatching { BrainHost.spar() }.getOrNull()
                                if (turn == null) {
                                    Toast.makeText(context, "体系图里还没东西可下，先在对话里说‘记下’几条", Toast.LENGTH_LONG).show()
                                } else {
                                    com.moling.app.live.LiveScreens.nextChoices = turn.choices
                                    runCatching { com.moling.app.live.LiveScreens.peek(context, turn.mood, turn.line) }
                                }
                            }
                        }) { Text("来一局", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            val byHour = com.moling.core.spar.Rpm.byHour(state.mindMarks, com.moling.core.flow.Flow.dayStart(today))
            val barsProgress = rememberInkProgress(order = 1, duration = 800)
            androidx.compose.foundation.Canvas(Modifier.width(88.dp).height(56.dp)) {
                val n = 24
                val gap = size.width / n
                val base = size.height
                val maxV = (byHour.maxOrNull() ?: 0).coerceAtLeast(6)
                for (h in 0 until n) {
                    val v = byHour.getOrElse(h) { 0 }
                    if (v > 0) {
                        val barH = (size.height - 4.dp.toPx()) * v / maxV * barsProgress.value
                        drawLine(
                            com.moling.app.ui.art.WashViolet.copy(alpha = 0.35f + 0.5f * v / maxV),
                            androidx.compose.ui.geometry.Offset(gap * h + gap / 2, base),
                            androidx.compose.ui.geometry.Offset(gap * h + gap / 2, base - barH),
                            (gap * 0.55f).coerceAtMost(5.dp.toPx()),
                            androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            }
        }

        // 4.5 今天的手机：哪几个小时被拉走、用得最多的、拿起几次（只有应用和时间）
        val usageToday = state.usageDays.lastOrNull { it.day == today }
        if (usageToday == null || usageToday.screenMin <= 0) {
            SectionTitle("今天的手机")
            Text(
                "用一会儿手机，这里会出现 24 小时的娱乐分布、最常打开的应用和拿起次数。只看应用和时间，不读内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SectionTitle("今天的手机")
            HourStrip(usageToday.playByHour, Flow.minuteOf(now) / 60)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮屏 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedCount(usageToday.screenMin, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" 分钟 · 娱乐 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedCount(usageToday.playMin, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" 分钟 · 拿起 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedCount(usageToday.pickups, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "最长连刷 " + usageToday.longestPlay + " 分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val maxTop = usageToday.top.firstOrNull()?.substringAfterLast(' ')?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            usageToday.top.take(5).forEach { t ->
                val name = t.substringBeforeLast(' ')
                val min = t.substringAfterLast(' ').toIntOrNull() ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(88.dp), maxLines = 1)
                    androidx.compose.foundation.Canvas(Modifier.weight(1f).height(8.dp)) {
                        val w = size.width * min / maxTop
                        drawLine(Ink.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1f)
                        drawLine(Ink.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(w, size.height / 2), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    Text(" $min 分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 4.6 这一周：7 天 × 24 小时的小方格，看哪几个小时总被娱乐拉走。
        // 少于 3 天还看不出规律，就先不显示这一节。
        val week = state.usageDays.takeLast(7)
        if (week.size >= 3) {
            SectionTitle("这一周")
            WeekGrid(week)
            Text(
                "每格 1 小时；颜色越深，那一小时刷得越多。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 「22–24 点（平均 48 分钟）」这种句子；一条都没有就不说。
            val weak = UsageLog.weakHours(week)
            if (weak.isNotEmpty()) {
                Text(
                    "这一周最容易被拉走：" + weak.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 5. 今天的预言：只显示今天下的注；对账过的标“对了 / 错了”。
        SectionTitle("今天的预言")
        val todayPredictions = state.predictions.filter { Brain.calendarDay(it.madeAt) == today }
        if (todayPredictions.isEmpty()) {
            Text(
                "今天还没有预言。多记几天，她才知道该对什么下注。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            todayPredictions.forEach { prediction -> PredictionRow(prediction) }
        }
        Text(
            Predict.scoreText(state.predictions),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 6. 最近做了什么：给人看的行动日志，只留最后 10 条。
        if (state.log.isNotEmpty()) {
            SectionTitle("最近做了什么")
            state.log.takeLast(10).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    // 去聊：开场白弹窗（复制 / 打开演员）；通知条和提议卡也共用它。
    opening?.let { op ->
        OpeningDialog(opening = op, clipboard = clipboard, onDismiss = { opening = null })
    }

    // 「有结论」：一句话收线，交给导演接着整理。
    conclusionCard?.let { c ->
        AlertDialog(
            onDismissRequest = { conclusionCard = null },
            title = { Text("有结论") },
            text = {
                Column {
                    Text(
                        "一句话，聊出了什么？下一步做什么，几点？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = conclusionText,
                        onValueChange = { conclusionText = it },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = conclusionText.trim()
                        if (text.isNotEmpty()) {
                            conclusionCard = null
                            conclusionText = ""
                            sendReply(Reply.Conclusion(c.id, System.currentTimeMillis(), text))
                        }
                    },
                    enabled = conclusionText.isNotBlank(),
                ) { Text("记下") }
            },
            dismissButton = { TextButton(onClick = { conclusionCard = null }) { Text("取消") } },
        )
    }
}

/** 小节标题。 */
@Composable
private fun SectionTitle(text: String) {
    InkTitle(text, Modifier.padding(top = 6.dp).inkEnter(distance = 6.dp))
}

/** 提醒条：一句提醒加一个“×”；挂着提议时带“好 / 不用”。 */
@Composable
private fun NoticeBar(
    notice: Notice,
    onDismiss: () -> Unit,
    onAccept: (() -> Unit)?,
    onDecline: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().inkEnter(distance = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                IconButton(onClick = onDismiss) {
                    Text(
                        "×",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onAccept != null && onDecline != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDecline) { Text("不用") }
                    TextButton(onClick = onAccept) { Text("好") }
                }
            }
        }
    }
}

/** 水道的一行：时间、种类、标题；正在流的那条加底色、加粗。 */
@Composable
private fun ChannelRow(channel: Channel, current: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inkEnter(distance = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${Flow.fmt(channel.startMin)}–${Flow.fmt(channel.endMin)}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        KindGlyph(channel.kind, current)
        Spacer(Modifier.width(8.dp))
        Text(
            channel.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (channel.state != ChannelState.WAIT) {
            Spacer(Modifier.width(6.dp))
            Text(
                channel.state.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一条预言：说什么、多大把握、对账没有。 */
@Composable
private fun PredictionRow(prediction: Prediction) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            prediction.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${(prediction.p * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (prediction.hit) {
            true -> {
                Spacer(Modifier.width(8.dp))
                Text("对了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            false -> {
                Spacer(Modifier.width(8.dp))
                Text("错了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            null -> {}
        }
    }
}

/**
 * 「此刻」卡片：导演说的一句话 + 可以念给演员的话 + 回应按钮。
 * 只显示和回调，真正回应由 [BrainHost.reply] 交给导演。
 */
@Composable
private fun DirectorCardView(
    card: MindCard,
    onDismiss: () -> Unit,
    onReply: (Reply) -> Unit,
    onPick: (Choice) -> Unit,
    onConclusion: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().inkEnter(distance = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Text(
                        "×",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                card.ask,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            card.sayLines.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "▸ $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    )
                    TextButton(onClick = { onCopy(line) }) { Text("复制") }
                }
            }
            if (card.queued > 1) {
                Text(
                    "还有 ${card.queued - 1} 件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.choices.isEmpty()) {
                // 默认四个按钮：继续 / 卡住了 / 有结论 / 休息。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        onClick = { onReply(Reply.Continue(card.id, System.currentTimeMillis())) },
                        modifier = Modifier.weight(1f),
                    ) { Text("继续") }
                    TextButton(
                        onClick = { onReply(Reply.Stuck(card.id, System.currentTimeMillis())) },
                        modifier = Modifier.weight(1f),
                    ) { Text("卡住了") }
                    TextButton(onClick = onConclusion, modifier = Modifier.weight(1f)) { Text("有结论") }
                    TextButton(
                        onClick = { onReply(Reply.Rest(card.id, System.currentTimeMillis())) },
                        modifier = Modifier.weight(1f),
                    ) { Text("休息") }
                }
            } else {
                // 点选题：按导演给的顺序，一个选项一个按钮。
                Column(Modifier.fillMaxWidth()) {
                    card.choices.forEach { choice ->
                        TextButton(onClick = { onPick(choice) }, modifier = Modifier.fillMaxWidth()) {
                            Text(choice.label)
                        }
                    }
                }
            }
        }
    }
}

/** 开场白对话框：长文可滚动；“复制”放进剪贴板；装了演员就能直接打开。 */
@Composable
private fun OpeningDialog(opening: Opening, clipboard: ClipboardManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 演员包名可能为空（NONE），手机也可能没装：取不到启动入口就不显示“打开”，只留复制。
    val launchIntent = remember(opening.actor, context) {
        val pkg = opening.actor.pkg
        if (pkg.isBlank()) null else runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开场白") },
        text = {
            Column {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(opening.text, style = MaterialTheme.typography.bodyMedium)
                }
                launchIntent?.let { intent ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            // 打开失败（被系统拦、演员被卸了）只提示，不关对话框，方便接着复制。
                            val actorStartedAt = System.currentTimeMillis()
                            val ok = runCatching { context.startActivity(intent) }.isSuccess
                            if (ok) {
                                com.moling.app.overlay.Presence.startSession(context, opening, actorStartedAt)
                                onDismiss()
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    "没打开，复制开场白去粘贴吧",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Text("打开 ${opening.actor.label}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(opening.text))
                Toast.makeText(context.applicationContext, "开场白复制了", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        },
    )
}

/** 水道种类的小插画：主河道 = 山，支流 = 叶，源头 = 对话，水潭 = 云，自由水域 = 水纹。只有正在流的那条会动。 */
@Composable
private fun KindGlyph(kind: ChannelKind, current: Boolean) {
    val m = Modifier.size(24.dp)
    when (kind) {
        ChannelKind.MAIN -> InkMountain(m)
        ChannelKind.SIDE -> InkLeaf(0.6f, m)
        ChannelKind.SPRING -> InkBubbles(m, animated = current)
        ChannelKind.POOL -> InkClouds(m, animated = current)
        ChannelKind.FREE -> InkWaves(m, lines = 6, animated = current)
    }
}

/** 24 小时的娱乐分布：每小时一根细墨线，越长刷得越多；现在这个小时下面一个小点。 */
@Composable
private fun HourStrip(byHour: List<Int>, hourNow: Int) {
    val progress = rememberInkProgress(duration = 750)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val n = 24
        val gap = size.width / n
        val base = size.height - 10.dp.toPx()
        val top = 4.dp.toPx()
        val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(20)
        drawLine(Ink.copy(alpha = 0.2f), androidx.compose.ui.geometry.Offset(0f, base), androidx.compose.ui.geometry.Offset(size.width, base), 0.7.dp.toPx())
        for (h in 0 until n) {
            val x = gap * h + gap / 2
            val m = byHour.getOrElse(h) { 0 }
            if (m > 0) {
                val y = base - (base - top) * m / max * progress.value
                drawLine(
                    com.moling.app.ui.art.WashOrange.copy(alpha = 0.35f + 0.55f * m / max),
                    androidx.compose.ui.geometry.Offset(x, base), androidx.compose.ui.geometry.Offset(x, y),
                    (gap * 0.42f).coerceAtMost(6.dp.toPx()), androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            if (h % 6 == 0) drawCircle(Ink.copy(alpha = 0.35f), 1.2.dp.toPx(), androidx.compose.ui.geometry.Offset(x, base + 5.dp.toPx()))
        }
        drawCircle(Seal, 2.4.dp.toPx(), androidx.compose.ui.geometry.Offset(gap * hourNow + gap / 2, base + 6.dp.toPx()))
    }
}

/** 星期几的中文：「一」到「日」。day 是 UsageDay.day（yyyy-MM-dd），解析不了就不猜。 */
private val WEEKDAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

private fun weekdayCn(day: String): String =
    runCatching { WEEKDAY_CN[java.time.LocalDate.parse(day).dayOfWeek.value - 1] }.getOrDefault("·")

/**
 * 「这一周」的小方格：一行一天（按日期从旧到新），一格一小时，共 7 行 × 24 格。
 * 颜色深浅 = 那一小时的娱乐分钟数：用 [WashOrange] 的透明度，60 分钟刷满最深；0 分钟就是纸色（不画）。
 * 左边一列写星期几，最下面一行标 0 / 6 / 12 / 18 点。
 * 只读：数据是 [UsageLog] 存下来的 [UsageDay]，不调模型，也不改任何状态。
 */
@Composable
private fun WeekGrid(week: List<UsageDay>) {
    val labelW = 22.dp
    val gutter = 6.dp
    // 每行 16dp：行高固定，左边的星期几和右边的方格才对得齐。
    val gridH = (16 * week.size).dp
    val progress = rememberInkProgress(duration = 850)

    Column(Modifier.fillMaxWidth().inkEnter(distance = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            // 左边星期几：每格和方格的一行一样高，文字居中。
            Column(Modifier.width(labelW).height(gridH)) {
                week.forEach { d ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            weekdayCn(d.day),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(gutter))
            Canvas(Modifier.weight(1f).height(gridH)) {
                val rows = week.size.coerceAtLeast(1)
                val colW = size.width / 24f
                val rowH = size.height / rows
                // 每格留一点缝，看着才有「格」的样子；0 分钟不画，露出纸色。
                val padX = 0.5.dp.toPx()
                val padY = 1.2.dp.toPx()
                val corner = 1.5.dp.toPx()
                week.forEachIndexed { r, d ->
                    for (h in 0 until 24) {
                        val min = d.playByHour.getOrElse(h) { 0 }
                        if (min <= 0) continue
                        // 一小时最多按 60 分钟算：5 分钟很浅，刷满一小时最深，不随这周最高值放大。
                        val t = (min / 60f).coerceIn(0f, 1f)
                        // 逐列从左到右显出来，像墨一点点渗开。
                        val reveal = ((progress.value * 24f - h) / 4f).coerceIn(0f, 1f)
                        if (reveal <= 0f) continue
                        drawRoundRect(
                            color = WashOrange.copy(alpha = (0.15f + 0.75f * t) * reveal),
                            topLeft = Offset(h * colW + padX, r * rowH + padY),
                            size = Size(
                                (colW - padX * 2).coerceAtLeast(1f),
                                (rowH - padY * 2).coerceAtLeast(1f),
                            ),
                            cornerRadius = CornerRadius(corner, corner),
                        )
                    }
                }
            }
        }
        // 最下面一行小刻度：0 / 6 / 12 / 18 点；刻度线和方格左边对齐。
        Row(Modifier.fillMaxWidth().padding(start = labelW + gutter, top = 2.dp)) {
            listOf("0", "6", "12", "18").forEach { h ->
                Column(Modifier.weight(1f)) {
                    Box(Modifier.width(1.dp).height(3.dp).background(Ink.copy(alpha = 0.3f)))
                    Text(
                        h,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
