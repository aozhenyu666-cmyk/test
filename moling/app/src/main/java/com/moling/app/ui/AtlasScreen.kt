package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.atlas.AtlasOps
import com.moling.core.atlas.Edge
import com.moling.core.atlas.Gap
import com.moling.core.atlas.Node
import com.moling.core.atlas.NodeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 体系页独用的协程域：切主题、收材料都不跟着页面取消。 */
private val atlasScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 体系页（M7-b 最小实现）：主题、论断、关系、缺口、贴材料。
 *
 * 从上到下：空态说明 → 主题（横向 FilterChip）→ 当前主题的论断（按种类分组）→
 * 关系 → 缺口 · 下次该聊 → 贴材料。
 * 只显示和转发：贴材料才调模型（[BrainHost.ingest]），其余都是读 [BrainHost.state]。
 * 每步 runCatching：模型没接上、材料太少都只 Toast，界面不卡。
 *
 * 等正式 M7 文档再对齐：这一版没做体系总结（synthesis）、删除 / 编辑论断和验证操作。
 */
@Composable
fun AtlasScreen() {
    val context = LocalContext.current
    val state by BrainHost.state.collectAsState()
    val atlas = state.atlas
    val currentTopic = atlas.topics.firstOrNull { it.id == state.topicId } ?: atlas.topics.firstOrNull()

    var input by remember { mutableStateOf("") }
    // 默认填当前主题名；切主题时跟着换，没有主题就用“杂项”。
    var topicInput by remember(currentTopic?.id) { mutableStateOf(currentTopic?.name ?: "杂项") }
    var ingesting by remember { mutableStateOf(false) }

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

                // 3. 当前主题的论断：按事实 / 判断 / 假设 / 问题 / 方法分组。
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
                            group.forEach { node -> NodeRow(node) }
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

                // 5. 缺口 + 下次该聊：缺口就是下一次去 GPT / Gemini 要问的问题。
                val gaps = AtlasOps.gaps(atlas, topic.id)
                SectionTitle("缺口 · 下次该聊")
                if (gaps.isEmpty()) {
                    Text(
                        "这张网现在没有明显缺口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    gaps.forEach { gap -> GapRow(gap) }
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

/** 一条论断：正文一行，下面小字写把握、验证结果和来源。 */
@Composable
private fun NodeRow(node: Node) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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

/** 一条缺口：哪里没织上、为什么、下次该问什么（问题加粗）。 */
@Composable
private fun GapRow(gap: Gap) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "【${gap.kind}】${gap.why}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            gap.question,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
