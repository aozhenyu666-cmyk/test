package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.Proposal
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Edge
import com.moling.core.atlas.Gap
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import com.moling.core.mind.Actor
import com.moling.core.mind.Opening
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 体系页独用的协程域：切主题、收材料、核实、归纳、去聊都不跟着页面取消。 */
private val atlasScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 体系页（M7-b 最小实现 + M14 补全）：主题、论断、关系、缺口、贴材料。
 *
 * 从上到下：空态说明 → 主题（横向 FilterChip）→ 归纳这个主题 → 论断（按种类分组，
 * 每条右边「⋯」可核实 / 删除）→ 关系 → 缺口 · 下次该聊（每个缺口可「去聊」，提议用卡片显示）
 * → 贴材料。
 * 只显示和转发：动模型 / 动现实的都由 [BrainHost] 做，用户点「好」之前什么都不执行。
 * 每步 runCatching：模型没接上、总控连不上、演员没装都只 Toast，界面不卡。
 */
@Composable
fun AtlasScreen() {
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    val state by BrainHost.state.collectAsState()
    val atlas = state.atlas
    val currentTopic = atlas.topics.firstOrNull { it.id == state.topicId } ?: atlas.topics.firstOrNull()

    var input by remember { mutableStateOf("") }
    // 默认填当前主题名；切主题时跟着换，没有主题就用“杂项”。
    var topicInput by remember(currentTopic?.id) { mutableStateOf(currentTopic?.name ?: "杂项") }
    var ingesting by remember { mutableStateOf(false) }
    var synthesizing by remember { mutableStateOf(false) }

    // 每条论断的「⋯」菜单弹出来的两个对话框：核实 / 删除。
    var verifyingNode by remember { mutableStateOf<Node?>(null) }
    var verifyText by remember { mutableStateOf("") }
    var deletingNode by remember { mutableStateOf<Node?>(null) }

    // 缺口「去聊」：先选演员，再把返回的提议挂在那个缺口下面（key = nodeId + kind）。
    var chattingGap by remember { mutableStateOf<Gap?>(null) }
    val gapProposals = remember { mutableStateMapOf<String, Proposal>() }

    // 点「好」或「重试」之后，大脑给的开场白（去聊）弹窗。
    var opening by remember { mutableStateOf<Opening?>(null) }

    fun toast(text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    fun switchTopic(name: String) {
        atlasScope.launch { runCatching { BrainHost.setTopic(name) } }
    }

    fun ingest() {
        val body = input.trim()
        if (body.isEmpty() || ingesting) return
        ingesting = true
        atlasScope.launch {
            try {
                val n = runCatching {
                    BrainHost.ingest(body, "用户", topicInput.trim().ifBlank { "杂项" })
                }.getOrDefault(0)
                if (n > 0) {
                    input = ""
                    toast("收进 $n 条")
                } else {
                    toast("没拆出新论断（模型没接上，或内容太少）")
                }
            } finally {
                ingesting = false
            }
        }
    }

    fun synthesize() {
        if (synthesizing) return
        synthesizing = true
        atlasScope.launch {
            try {
                val text = runCatching { BrainHost.synthesize() }.getOrNull()
                // 成功时结果已经写进 state.synthesis[topicId]，卡片会自己出现；失败才提示。
                if (text == null) toast("现在归纳不了（模型没接上）")
            } finally {
                synthesizing = false
            }
        }
    }

    fun verify(node: Node) {
        val result = verifyText.trim()
        if (result.isEmpty()) return
        atlasScope.launch {
            val ok = runCatching { BrainHost.verifyNode(node.id, result) }.getOrDefault(false)
            if (ok) {
                verifyingNode = null
                toast("记下了")
            } else {
                toast("现在核实不了")
            }
        }
    }

    fun removeNode(node: Node) {
        atlasScope.launch {
            runCatching { BrainHost.removeNode(node.id) }
            deletingNode = null
            toast("删了")
        }
    }

    fun askGap(gap: Gap, actor: Actor) {
        chattingGap = null
        atlasScope.launch {
            val p = runCatching { BrainHost.askGap(gap.question, actor) }.getOrNull()
            if (p == null) {
                toast("现在去不了，等会儿再试")
            } else {
                gapProposals[gap.nodeId + gap.kind] = p
            }
        }
    }

    fun acceptProposal(p: Proposal) {
        atlasScope.launch {
            val out = runCatching { BrainHost.accept(p.id, System.currentTimeMillis()) }.getOrNull()
            if (out == null) {
                toast("没接上，再试一次")
            } else {
                toast(out.message)
                out.opening?.let { opening = it }
            }
        }
    }

    fun declineProposal(p: Proposal) {
        atlasScope.launch { runCatching { BrainHost.decline(p.id, System.currentTimeMillis()) } }
    }

    fun retryProposal(p: Proposal) {
        atlasScope.launch {
            val out = runCatching { BrainHost.retry(p.id, System.currentTimeMillis()) }.getOrNull()
            if (out == null) {
                toast("没接上，再试一次")
            } else {
                toast(out.message)
                out.opening?.let { opening = it }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (atlas.topics.isEmpty()) {
            // 1. 空态：还没有任何主题，只留说明和下面的贴材料区。
            Text(
                "还没有体系图。贴一段聊出来的结论，或从对话里点「记下」开始。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 2. 主题：横着滚的一排，当前主题高亮。
            SectionTitle("主题")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                atlas.topics.forEach { t ->
                    FilterChip(
                        selected = t.id == currentTopic?.id,
                        onClick = { switchTopic(t.name) },
                        label = { Text(t.name) },
                    )
                }
            }

            val topic = currentTopic
            if (topic != null) {
                val nodes = atlas.nodes.filter { it.topicId == topic.id }

                // 2.5 归纳这个主题：模型给的主干 / 分支 / 关键链 / 最大缺口，存在 state.synthesis。
                Button(
                    onClick = { synthesize() },
                    enabled = !synthesizing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (synthesizing) "在归纳…" else "归纳这个主题")
                }
                val summary = state.synthesis[topic.id].orEmpty()
                if (summary.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            summary,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 3. 当前主题的论断：按事实 / 判断 / 假设 / 问题 / 方法分组；每条右边「⋯」核实 / 删除。
                SectionTitle("论断")
                if (nodes.isEmpty()) {
                    Text(
                        "这个主题还没有论断",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    NodeKind.entries.forEach { kind ->
                        val group = nodes.filter { it.kind == kind }
                        if (group.isNotEmpty()) {
                            Text(
                                kind.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            group.forEach { node ->
                                NodeRow(
                                    node = node,
                                    onVerify = {
                                        verifyText = ""
                                        verifyingNode = node
                                    },
                                    onDelete = { deletingNode = node },
                                )
                            }
                        }
                    }
                }

                // 4. 关系：只显示两端都在当前主题里的。没有就不显示这一节。
                val ids = nodes.map { it.id }.toSet()
                val edges = atlas.edges.filter { it.from in ids && it.to in ids }
                if (edges.isNotEmpty()) {
                    SectionTitle("关系")
                    val byId = nodes.associateBy { it.id }
                    edges.forEach { edge ->
                        val from = byId[edge.from]
                        val to = byId[edge.to]
                        if (from != null && to != null) EdgeRow(edge, from, to)
                    }
                }

                // 5. 缺口 + 下次该聊：每个缺口可以「去聊」，返回的提议挂在这个缺口下面。
                val gaps = AtlasOps.gaps(atlas, topic.id)
                SectionTitle("缺口 · 下次该聊")
                if (gaps.isEmpty()) {
                    Text(
                        "这张网现在没有明显缺口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    gaps.forEach { gap ->
                        // 本地存的提议可能已经旧了：优先用 state.proposals 里的最新状态。
                        val stored = gapProposals[gap.nodeId + gap.kind]
                        val live = stored?.let { p -> state.proposals.firstOrNull { it.id == p.id } ?: p }
                        GapRow(
                            gap = gap,
                            proposal = live,
                            onChat = { chattingGap = gap },
                            onAccept = { acceptProposal(it) },
                            onDecline = { declineProposal(it) },
                            onRetry = { retryProposal(it) },
                        )
                    }
                }
            }
        }

        // 6. 贴材料：收进体系图（调一次模型，拆成论断和关系）。
        SectionTitle("贴材料")
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 4000) input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("把 GPT / Gemini 聊出来的结论贴进来") },
            minLines = 3,
            maxLines = 8,
        )
        OutlinedTextField(
            value = topicInput,
            onValueChange = { if (it.length <= 30) topicInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("主题") },
            placeholder = { Text("杂项") },
        )
        Button(
            onClick = { ingest() },
            enabled = input.isNotBlank() && !ingesting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ingesting) "在拆…" else "收进体系图")
        }

        Spacer(Modifier.height(12.dp))
    }

    // 核实：输入验证结果，成功才关弹窗（失败还能改一改再试）。
    verifyingNode?.let { node ->
        AlertDialog(
            onDismissRequest = { verifyingNode = null },
            title = { Text("核实") },
            text = {
                Column {
                    Text(
                        node.claim,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = verifyText,
                        onValueChange = { verifyText = it },
                        label = { Text("验证结果是什么？") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { verify(node) }, enabled = verifyText.isNotBlank()) { Text("记下") }
            },
            dismissButton = { TextButton(onClick = { verifyingNode = null }) { Text("取消") } },
        )
    }

    // 删除：二次确认，关系一起删。
    deletingNode?.let { node ->
        AlertDialog(
            onDismissRequest = { deletingNode = null },
            title = { Text("删掉这条论断？") },
            text = { Text("和它相连的关系也一起删。") },
            confirmButton = { TextButton(onClick = { removeNode(node) }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { deletingNode = null }) { Text("取消") } },
        )
    }

    // 去聊：选一个演员，生成一条「去聊」提议（点「好」才去）。
    chattingGap?.let { gap ->
        AlertDialog(
            onDismissRequest = { chattingGap = null },
            title = { Text("去聊这个缺口") },
            text = {
                Column {
                    Text(
                        gap.question,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { askGap(gap, Actor.GEMINI_LIVE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Gemini") }
                    TextButton(
                        onClick = { askGap(gap, Actor.CHATGPT) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ChatGPT") }
                }
            },
            confirmButton = { TextButton(onClick = { chattingGap = null }) { Text("取消") } },
        )
    }

    // 开场白：显示大脑准备好的那段话，可以复制，装了演员就能直接打开。
    opening?.let { op ->
        AtlasOpeningDialog(opening = op, clip = clip, onDismiss = { opening = null })
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
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 一条论断：正文 + 小字（把握 / 已验证 / 来源），右边「⋯」弹核实 / 删除。 */
@Composable
private fun NodeRow(node: Node, onVerify: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                node.claim,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildString {
                append("把握 ${node.confidence}")
                if (node.verified.isNotBlank()) append(" · 已验证：${node.verified}")
                if (node.source.isNotBlank()) append(" · 来源 ${node.source}")
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Text("⋯", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("核实") },
                    onClick = {
                        menuOpen = false
                        onVerify()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 一条关系：“A 的 claim” + 关系 + “B 的 claim”；有说明就再写一行小字。 */
@Composable
private fun EdgeRow(edge: Edge, from: Node, to: Node) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "${from.claim} ${edge.relation.label} ${to.claim}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (edge.note.isNotBlank()) {
            Text(
                edge.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一条缺口：哪里没织上、为什么、下次该问什么（问题加粗）。
 * 「去聊」生成提议后，卡片就显示在这个缺口下面。
 */
@Composable
private fun GapRow(
    gap: Gap,
    proposal: Proposal?,
    onChat: () -> Unit,
    onAccept: (Proposal) -> Unit,
    onDecline: (Proposal) -> Unit,
    onRetry: (Proposal) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "【${gap.kind}】${gap.why}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChat) { Text("去聊") }
        }
        Text(
            gap.question,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (proposal != null) {
            ProposalCard(
                proposal = proposal,
                onAccept = onAccept,
                onDecline = onDecline,
                onRetry = onRetry,
            )
        }
    }
}

/** 开场白对话框：长文可滚动；“复制”放进剪贴板；“打开演员”直接启动 App（没装或启动失败只提示）。 */
@Composable
private fun AtlasOpeningDialog(opening: Opening, clip: ClipboardManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
                if (opening.actor.pkg.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            // 取启动入口和启动都可能失败（演员被卸了、被系统拦），只提示，不关对话框。
                            val intent = runCatching {
                                context.packageManager.getLaunchIntentForPackage(opening.actor.pkg)
                            }.getOrNull()
                            val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
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
                clip.setText(AnnotatedString(opening.text))
                Toast.makeText(context.applicationContext, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        },
    )
}
