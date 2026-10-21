package com.moling.app.ui

import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.Brain
import com.moling.core.Notice
import com.moling.core.Proposal
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
fun TodayScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by BrainHost.state.collectAsState()
    val notices by BrainHost.notices.collectAsState()

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

        // 2. 今天的水道
        SectionTitle("今天的水道")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Flow.fmt(Flow.minuteOf(now)),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                flow?.intent ?: "今天还没排",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(bottom = 6.dp),
            )
        }

        if (flow == null) {
            Text(
                "排一条，水才知道往哪流。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { planDay(false) }, enabled = !planning) {
                Text(if (planning) "在排…" else "排今天的水道")
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
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
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
        modifier = Modifier.fillMaxWidth(),
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
        Spacer(Modifier.width(8.dp))
        Text(
            channel.kind.short,
            style = MaterialTheme.typography.labelMedium,
            color = if (current) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        modifier = Modifier.fillMaxWidth(),
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
                            val ok = runCatching { context.startActivity(intent) }.isSuccess
                            if (ok) {
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
