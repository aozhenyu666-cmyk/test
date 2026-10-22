@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.moling.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moling.app.BrainHost
import com.moling.app.live.RadioPanel
import com.moling.core.workspace.ArtifactActionKind
import com.moling.core.workspace.ArtifactBlock
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.Focus
import com.moling.core.workspace.FocusStatus
import com.moling.core.workspace.JobKind
import com.moling.core.workspace.JobStatus
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.PreworkJob
import com.moling.core.workspace.WorkbenchState
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 第九批 I9「案板」：贴材料 → 她先做一点 → 他挑刺 / 采用 / 改 → 拿走一份稿子。
 * 只读 BrainHost.workbench 渲染，动作全部经 BrainHost；不自动开作业、不自动采用。
 */
private val benchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

@Composable
fun BenchScreen() {
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    val ws by BrainHost.workbench.collectAsState()
    val fetching by BrainHost.fetching.collectAsState()
    val focus = ws.active()
    var pasteOpen by remember { mutableStateOf(false) }
    var atlasOpen by remember { mutableStateOf(false) }
    var emptyOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    var webOpen by remember { mutableStateOf(false) }
    var materialsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<WorkspaceArtifact, ArtifactBlock>?>(null) }
    var rewriteTarget by remember { mutableStateOf<Pair<WorkspaceArtifact, ArtifactBlock>?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<WorkspaceArtifact, ArtifactBlock>?>(null) }
    var versionsOf by remember { mutableStateOf<WorkspaceArtifact?>(null) }
    var claimsOf by remember { mutableStateOf<WorkspaceArtifact?>(null) }

    fun toast(msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()

    fun apply(a: WorkspaceArtifact, kind: ArtifactActionKind, blockId: String? = null, optionId: String? = null, text: String? = null) {
        benchScope.launch {
            var r = BrainHost.benchApply(a, kind, blockId, optionId, text)
            // 他亲手写好的一段别因为“版本变了”就丢：那一段本身没被动过（比如只是别处的挑刺回来了），就在新版本上再存一次
            if (r is PortResult.Failed && r.error.code == PortError.REVISION_CONFLICT && kind == ArtifactActionKind.EDIT_BLOCK) {
                val before = a.blocks.firstOrNull { it.id == blockId }?.markdown
                val cur = BrainHost.workbench.value.artifact(a.id)
                if (cur != null && before != null && cur.blocks.firstOrNull { it.id == blockId }?.markdown == before) {
                    r = BrainHost.benchApply(cur, kind, blockId, optionId, text)
                }
            }
            // BUSY = 这一下还在做，按钮本来就在转圈，不用再弹
            if (r is PortResult.Failed && r.error.code != PortError.BUSY) toast(r.error.message)
        }
    }

    fun prework(kind: JobKind, artifactId: String? = null) {
        val f = focus ?: return
        benchScope.launch {
            when (val r = BrainHost.benchPrework(f.id, kind, artifactId)) {
                is PortResult.Ok -> {}
                is PortResult.Failed -> toast(r.error.message)
            }
        }
    }

    fun start(question: String, material: String) {
        benchScope.launch {
            val f = runCatching { BrainHost.benchStart(question, material) }.getOrNull()
            if (f == null) toast("没开起来，再试一次") else toast("开好了，替你先做一块？")
        }
    }

    if (focus == null) {
        BenchHome(
            ws = ws,
            onPaste = { pasteOpen = true },
            onAtlas = { atlasOpen = true },
            onSkeleton = { emptyOpen = true },
            onSwitch = { f -> benchScope.launch { BrainHost.benchSwitch(f.id) } },
        )
    } else {
        val jobs = ws.jobs.filter { it.focusId == focus.id && it.status == JobStatus.RUNNING }
        val artifacts = ws.artifactsOf(focus.id).sortedByDescending { it.createdAt }
        BenchFocus(
            ws = ws,
            focus = focus,
            artifacts = artifacts,
            running = jobs,
            menuOpen = menuOpen,
            onMenu = { menuOpen = true },
            onCloseMenu = { menuOpen = false },
            onNew = { menuOpen = false; newOpen = true },
            onWeb = { menuOpen = false; webOpen = true },
            onArchive = { menuOpen = false; benchScope.launch { BrainHost.benchArchive(focus.id) } },
            onSwitch = { f -> menuOpen = false; benchScope.launch { BrainHost.benchSwitch(f.id) } },
            onPaused = ws.focuses.filter { it.status == FocusStatus.PAUSED },
            fetching = focus.id in fetching,
            onCancelFetch = { benchScope.launch { BrainHost.cancelBenchFetch(focus.id) } },
            onMaterials = { materialsOpen = true },
            onPrework = { kind, artifactId -> prework(kind, artifactId) },
            onCancel = { jobId -> benchScope.launch { BrainHost.benchCancel(jobId) } },
            onApply = { a, kind, blockId, optionId, text -> apply(a, kind, blockId, optionId, text) },
            onEdit = { a, b -> editTarget = a to b },
            onRewrite = { a, b -> rewriteTarget = a to b },
            onDelete = { a, b -> deleteTarget = a to b },
            onVersions = { a -> versionsOf = a },
            onClaims = { a -> claimsOf = a },
            onExport = { a ->
                val md = BrainHost.benchMarkdown(a.id)
                if (md.isBlank()) toast("这份还没有可导出的正文") else {
                    clip.setText(AnnotatedString(md))
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, md)
                    runCatching { context.startActivity(Intent.createChooser(send, "导出稿子")) }
                    toast("已复制 Markdown")
                }
            },
        )
    }

    if (pasteOpen) StartPasteDialog(onClose = { pasteOpen = false }) { q, m -> pasteOpen = false; start(q, m) }
    if (emptyOpen) StartSkeletonDialog(onClose = { emptyOpen = false }) { q -> emptyOpen = false; start(q, "") }
    if (newOpen) StartPasteDialog(onClose = { newOpen = false }) { q, m -> newOpen = false; start(q, m) }
    if (webOpen) StartWebDialog(onClose = { webOpen = false }, onStart = { url ->
        webOpen = false
        val f = focus
        if (f != null) benchScope.launch {
            when (val r = BrainHost.benchFetchSource(f.id, url)) {
                is PortResult.Ok -> toast("取到网页了：" + r.value.title)
                is PortResult.Failed -> toast(r.error.message)
            }
        }
    })
    if (atlasOpen) AtlasPickDialog(ws = ws, onClose = { atlasOpen = false }, onPick = { id ->
        atlasOpen = false
        benchScope.launch {
            val f = runCatching { BrainHost.benchFromAtlas(id) }.getOrNull()
            if (f == null) toast("这个主题里还没有论断") else toast("开好了，替你先做一块？")
        }
    })

    val f = focus
    if (materialsOpen && f != null) MaterialsDialog(ws = ws, focus = f, onClose = { materialsOpen = false }, onAdd = { text ->
        benchScope.launch {
            when (val r = BrainHost.benchAddMaterial(f.id, text)) {
                is PortResult.Ok -> toast("补上了")
                is PortResult.Failed -> toast(r.error.message)
            }
        }
    }, onDeleteSource = { sourceId ->
        benchScope.launch { BrainHost.deleteSource(sourceId) }
    })

    editTarget?.let { (a, b) ->
        EditDialog(initial = b.markdown, onClose = { editTarget = null }) { text ->
            editTarget = null
            apply(a, ArtifactActionKind.EDIT_BLOCK, b.id, text = text)
        }
    }
    rewriteTarget?.let { (a, b) ->
        RewriteDialog(onClose = { rewriteTarget = null }) { ask ->
            rewriteTarget = null
            apply(a, ArtifactActionKind.REWRITE_BLOCK, b.id, text = ask.ifBlank { null })
        }
    }
    deleteTarget?.let { (a, b) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删掉这一段？") },
            text = { Text("只从这份稿子里删，不影响材料。") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; apply(a, ArtifactActionKind.DELETE_BLOCK, b.id) }) { Text("删") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("算了") } },
        )
    }
    versionsOf?.let { a ->
        val versions = ws.versions(a.id)
        AlertDialog(
            onDismissRequest = { versionsOf = null },
            title = { Text("版本") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("现在：第 " + a.revision + " 版", style = MaterialTheme.typography.labelMedium)
                    if (versions.isEmpty()) Text("还没有可撤销的版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    versions.asReversed().forEach { v ->
                        Text(hmShort(v.at) + " · " + v.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { versionsOf = null }) { Text("好") } },
        )
    }
    claimsOf?.let { a ->
        val claims = BrainHost.benchClaims(a.id)
        AlertDialog(
            onDismissRequest = { claimsOf = null },
            title = { Text("收进体系图") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (claims.isEmpty()) Text("先采用几段，才有能收进体系图的论断。", style = MaterialTheme.typography.bodySmall)
                    claims.forEach { Text("· " + it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = claims.isNotEmpty(), onClick = {
                    claimsOf = null
                    benchScope.launch {
                        val n = BrainHost.benchKeep(a.id)
                        Toast.makeText(context.applicationContext, "收进体系图 " + n + " 条", Toast.LENGTH_LONG).show()
                    }
                }) { Text("收进") }
            },
            dismissButton = { TextButton(onClick = { claimsOf = null }) { Text("算了") } },
        )
    }
}

@Composable
private fun BenchHome(ws: WorkbenchState, onPaste: () -> Unit, onAtlas: () -> Unit, onSkeleton: () -> Unit, onSwitch: (Focus) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("案板", style = MaterialTheme.typography.headlineSmall)
        Text(
            "贴两三句材料，她先做一份三点纲要；你挑刺、采用、改，最后拿走一份能复制或导出的稿子。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        BenchEntry("贴材料", "把你手上的原文、笔记或聊天结论贴进来，告诉她你要攻什么。", onPaste, 0)
        BenchEntry("从体系选", "从体系图里挑一个主题，用里面已有的论断当材料。", onAtlas, 1)
        BenchEntry("先给骨架", "什么都不贴，只写一句要攻的问题，让她先从零拆三点。", onSkeleton, 2)
        val paused = ws.focuses.filter { it.status == FocusStatus.PAUSED }
        if (paused.isNotEmpty()) {
            Text("收起的事", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            paused.forEach { f ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.7.dp, Ink.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth().clickable { onSwitch(f) },
                ) {
                    Text(f.question.ifBlank { "（没写问题）" }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BenchEntry(title: String, desc: String, onClick: () -> Unit, order: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, Ink.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            com.moling.app.ui.art.InkPapers(Modifier.size(44.dp), animated = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = Ink.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun BenchFocus(
    ws: WorkbenchState,
    focus: Focus,
    artifacts: List<WorkspaceArtifact>,
    running: List<PreworkJob>,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onNew: () -> Unit,
    onWeb: () -> Unit,
    onArchive: () -> Unit,
    onSwitch: (Focus) -> Unit,
    onPaused: List<Focus>,
    /** F10-7：这个焦点的网页正文正在后台取。 */
    fetching: Boolean,
    onCancelFetch: () -> Unit,
    onMaterials: () -> Unit,
    onPrework: (JobKind, String?) -> Unit,
    onCancel: (String) -> Unit,
    onApply: (WorkspaceArtifact, ArtifactActionKind, String?, String?, String?) -> Unit,
    onEdit: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onRewrite: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onDelete: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onVersions: (WorkspaceArtifact) -> Unit,
    onClaims: (WorkspaceArtifact) -> Unit,
    onExport: (WorkspaceArtifact) -> Unit,
) {
    var openId by remember(focus.id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(focus.question.ifBlank { "（没写问题）" }, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onMaterials, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("材料 " + ws.materialsOf(focus).size + " 条", style = MaterialTheme.typography.labelMedium)
                    }
                    val outs = artifacts.filter { it.kind == ArtifactKind.OUTLINE }
                    if (outs.isNotEmpty()) {
                        TextButton(onClick = { onPrework(JobKind.DRAFT, outs.last().id) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text("写成初稿", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Box {
                TextButton(onClick = onMenu) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                    DropdownMenuItem(text = { Text("开新的一件") }, onClick = onNew)
                    DropdownMenuItem(text = { Text("加网页 / 链接") }, onClick = onWeb)
                    DropdownMenuItem(text = { Text("收起这件") }, onClick = onArchive)
                    if (onPaused.isNotEmpty()) {
                        Text("切到别的事", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                        onPaused.forEach { pf ->
                            DropdownMenuItem(text = { Text(pf.question.ifBlank { "（没写问题）" }) }, onClick = { onSwitch(pf) })
                        }
                    }
                }
            }
        }
        // 第十一批 R2：只听电台（只念已有材料 / 稿子，不联网）
        RadioPanel(ws, focus)
        if (running.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.moling.app.ui.art.InkThinking("她在做：" + running.first().kind.label)
                TextButton(onClick = { onCancel(running.first().id) }) { Text("取消") }
            }
        }
        if (fetching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在取网页正文…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCancelFetch) { Text("取消") }
            }
        }
        if (artifacts.isNotEmpty()) {
            OutlinedButton(onClick = { onPrework(JobKind.OUTLINE, null) }, modifier = Modifier.fillMaxWidth()) { Text("替我先做一块（三点纲要）") }
        }
        if (artifacts.isEmpty()) {
            Button(onClick = { onPrework(JobKind.OUTLINE, null) }, modifier = Modifier.fillMaxWidth()) { Text("替我先做一块") }
            Text(focus.nextChoice ?: "她不会自己开工；你点了才会调模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val newest = artifacts.firstOrNull()
            val open = openId ?: newest?.id
            artifacts.forEach { a ->
                ArtifactCard(
                    ws = ws,
                    artifact = a,
                    expanded = a.id == open,
                    blockJob = { blockId -> running.firstOrNull { it.blockId == blockId } },
                    onToggle = { openId = if (a.id == open) "" else a.id },
                    onCancel = onCancel,
                    onApply = onApply,
                    onEdit = onEdit,
                    onRewrite = onRewrite,
                    onDelete = onDelete,
                    onVersions = onVersions,
                    onClaims = onClaims,
                    onExport = onExport,
                    onCouncil = { onPrework(JobKind.COUNCIL, a.id) },
                )
            }
            if (focus.nextChoice != null) Text(focus.nextChoice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtifactCard(
    ws: WorkbenchState,
    artifact: WorkspaceArtifact,
    expanded: Boolean,
    blockJob: (String) -> PreworkJob?,
    onToggle: () -> Unit,
    onCancel: (String) -> Unit,
    onApply: (WorkspaceArtifact, ArtifactActionKind, String?, String?, String?) -> Unit,
    onEdit: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onRewrite: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onDelete: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onVersions: (WorkspaceArtifact) -> Unit,
    onClaims: (WorkspaceArtifact) -> Unit,
    onExport: (WorkspaceArtifact) -> Unit,
    onCouncil: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.7.dp, Ink.copy(alpha = 0.16f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(artifact.title.ifBlank { kindLabel(artifact.kind) }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        kindLabel(artifact.kind) + " · 第 " + artifact.revision + " 版" + if (artifact.status == ArtifactStatus.ACCEPTED) " · 已采用" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!expanded) return@Column
            if (artifact.offline) {
                Text("模型没接上，这是从你的材料里拆的骨架。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            artifact.blocks.forEach { block ->
                when (block.kind) {
                    BlockKind.REVIEW_NOTE -> ReviewNoteBlock(artifact, block, onApply)
                    BlockKind.CHOICE -> ExerciseBlock(artifact, block, onApply)
                    else -> ContentBlock(
                        artifact = artifact,
                        block = block,
                        first = block.id == artifact.blocks.firstOrNull { it.kind == BlockKind.MARKDOWN || it.kind == BlockKind.QUOTE || it.kind == BlockKind.ASSUMPTION }?.id,
                        job = blockJob(block.id),
                        onCancel = onCancel,
                        onApply = onApply,
                        onEdit = onEdit,
                        onRewrite = onRewrite,
                        onDelete = onDelete,
                    )
                }
            }
            if (artifact.kind == ArtifactKind.COUNCIL_REVIEW) {
                Text("四参谋只是批注；要改正文，回到初稿那张卡。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 手机宽度放不下一排六个：自动折行
            val review = artifact.kind == ArtifactKind.COUNCIL_REVIEW
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(enabled = ws.versions(artifact.id).isNotEmpty(), onClick = { onApply(artifact, ArtifactActionKind.UNDO, null, null, null) }) { Text("撤销") }
                TextButton(onClick = { onVersions(artifact) }) { Text("版本") }
                // 四参谋那份只是批注：不采用、不再请四参谋、不收进体系图
                if (!review) TextButton(onClick = { onApply(artifact, ArtifactActionKind.ACCEPT_ARTIFACT, null, null, null) }) { Text("整份采用") }
                if (!review) TextButton(onClick = onCouncil) { Text("四参谋") }
                TextButton(onClick = { onExport(artifact) }) { Text("导出") }
                if (!review) TextButton(onClick = { onClaims(artifact) }) { Text("收进体系图") }
            }
        }
    }
}

@Composable
private fun ContentBlock(
    artifact: WorkspaceArtifact,
    block: ArtifactBlock,
    first: Boolean,
    job: PreworkJob?,
    onCancel: (String) -> Unit,
    onApply: (WorkspaceArtifact, ArtifactActionKind, String?, String?, String?) -> Unit,
    onEdit: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onRewrite: (WorkspaceArtifact, ArtifactBlock) -> Unit,
    onDelete: (WorkspaceArtifact, ArtifactBlock) -> Unit,
) {
    val quote = block.kind == BlockKind.QUOTE
    val assumption = block.kind == BlockKind.ASSUMPTION
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (assumption) Modifier.border(0.8.dp, Ink.copy(alpha = 0.3f), RoundedCornerShape(8.dp)) else Modifier)
            .padding(if (assumption) 8.dp else 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (quote) Box(Modifier.width(2.dp).height(18.dp).background(Ink.copy(alpha = 0.35f)))
            if (quote) Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (assumption) Text("待核", style = MaterialTheme.typography.labelSmall, color = Seal)
                MarkdownText(block.markdown)
            }
            if (block.accepted) Text("✓", style = MaterialTheme.typography.titleMedium, color = Seal)
        }
        SourceTags(block.sourceIds)
        if (job != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.moling.app.ui.art.InkThinking("她在" + job.kind.label)
                TextButton(onClick = { onCancel(job.id) }) { Text("取消") }
            }
        } else {
            FlowRow {
                TextButton(onClick = { onApply(artifact, ArtifactActionKind.REQUEST_CRITIC, block.id, null, null) }) { Text("挑刺") }
                TextButton(onClick = { onApply(artifact, ArtifactActionKind.ACCEPT_BLOCK, block.id, null, null) }) { Text("采用") }
                TextButton(onClick = { onEdit(artifact, block) }) { Text("改") }
                TextButton(onClick = { onRewrite(artifact, block) }) { Text(if (first) "改首段" else "重写") }
                TextButton(onClick = { onDelete(artifact, block) }) { Text("删") }
            }
        }
    }
}

@Composable
private fun ReviewNoteBlock(artifact: WorkspaceArtifact, block: ArtifactBlock, onApply: (WorkspaceArtifact, ArtifactActionKind, String?, String?, String?) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Seal.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(block.voice.ifBlank { "批注" }, style = MaterialTheme.typography.labelSmall, color = Seal)
        MarkdownText(block.markdown)
        if (block.options.isNotEmpty()) {
            var showRewrite by remember(block.id) { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                block.options.forEach { o ->
                    OutlinedButton(onClick = { onApply(artifact, ArtifactActionKind.CHOOSE_BRANCH, block.id, o.id, null) }) { Text(o.label.ifBlank { "选" }) }
                }
                TextButton(onClick = { showRewrite = !showRewrite }) { Text(if (showRewrite) "收起改后" else "看改后的整段") }
            }
            if (showRewrite) {
                // A 和 B 都给他看，才好比较着选
                val withText = block.options.filter { it.markdown.isNotBlank() }
                if (withText.isEmpty()) {
                    Text("这条批注没有附改后的整段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                withText.forEach { o ->
                    Text("选「" + o.label + "」后：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MarkdownText(o.markdown)
                }
            }
        }
    }
}

@Composable
private fun ExerciseBlock(artifact: WorkspaceArtifact, block: ArtifactBlock, onApply: (WorkspaceArtifact, ArtifactActionKind, String?, String?, String?) -> Unit) {
    var picked by remember(block.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().border(0.8.dp, Seal.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("练习", style = MaterialTheme.typography.labelSmall, color = Seal)
        MarkdownText(block.markdown)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            block.options.forEach { o ->
                OutlinedButton(onClick = { picked = o.id; onApply(artifact, ArtifactActionKind.CHOOSE_BRANCH, block.id, o.id, null) }) {
                    Text(o.label.ifBlank { "选" })
                }
            }
        }
        val chosen = block.options.firstOrNull { it.id == picked }
        if (chosen != null && chosen.explanation.isNotBlank()) {
            Text(chosen.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceTags(ids: List<String>) {
    if (ids.isEmpty()) return
    var openId by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ids.take(4).forEach { id ->
            val label = when {
                id.startsWith("m-") -> "材料"
                id.startsWith("n-") -> "体系"
                else -> "来源"
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Seal.copy(alpha = 0.85f),
                modifier = Modifier.clickable { openId = id },
            )
        }
    }
    val sid = openId
    if (sid != null) {
        val ws = BrainHost.workbench.value
        val material = ws.materials.firstOrNull { it.id == sid }
        val node = BrainHost.state.value.atlas.nodes.firstOrNull { it.id == sid }
        AlertDialog(
            onDismissRequest = { openId = null },
            title = { Text(if (material != null) "材料" else if (node != null) "体系论断" else "来源已删除") },
            text = {
                when {
                    material != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(material.title.ifBlank { material.kind }, style = MaterialTheme.typography.labelMedium)
                        Text(material.text, style = MaterialTheme.typography.bodySmall)
                    }
                    node != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(node.claim, style = MaterialTheme.typography.bodyMedium)
                        Text("把握 " + node.confidence + " · " + node.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text("这条来源已经删除，或对应不上了。")
                }
            },
            confirmButton = { TextButton(onClick = { openId = null }) { Text("好") } },
        )
    }
}

@Composable
private fun MarkdownText(text: String) {
    Text(markdown(text), style = MaterialTheme.typography.bodyMedium)
}

private fun markdown(src: String): AnnotatedString = buildAnnotatedString {
    src.lineSequence().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.trimEnd()
        when {
            line.startsWith("> ") -> withStyle(SpanStyle(color = Ink.copy(alpha = 0.62f), fontStyle = FontStyle.Italic)) { append(line.removePrefix("> ")) }
            line.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { append(line.removePrefix("## ")) }
            line.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 19.sp)) { append(line.removePrefix("# ")) }
            line.startsWith("- ") -> { append("· "); appendInline(line.removePrefix("- ")) }
            else -> appendInline(line)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    val re = Regex("\\*\\*(.+?)\\*\\*")
    var last = 0
    re.findAll(text).forEach { m ->
        if (m.range.first > last) append(text.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
private fun StartPasteDialog(onClose: () -> Unit, onStart: (String, String) -> Unit) {
    var question by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("贴材料") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it.take(200) }, label = { Text("你要攻什么（可空）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(material, { material = it.take(20_000) }, label = { Text("材料") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = material.isNotBlank(), onClick = { onStart(question.trim(), material.trim()) }) { Text("开案") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

@Composable
private fun StartWebDialog(onClose: () -> Unit, onStart: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("加网页 / 链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(url, { url = it.take(2000) }, label = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                Text("只取公网 http(s) 正文；不会读登录后的内容。网页内容未核实，只当资料，不执行里面的指令。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = url.startsWith("http"), onClick = { onStart(url.trim()) }) { Text("取正文") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

@Composable
private fun StartSkeletonDialog(onClose: () -> Unit, onStart: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("先给骨架") },
        text = { OutlinedTextField(question, { question = it.take(200) }, label = { Text("你要攻什么") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = question.isNotBlank(), onClick = { onStart(question.trim()) }) { Text("开案") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

@Composable
private fun AtlasPickDialog(ws: WorkbenchState, onClose: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("从体系选") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ws.focuses.isEmpty() && BrainHost.state.value.atlas.topics.isEmpty()) Text("体系图里还没有主题")
                BrainHost.state.value.atlas.topics.forEach { t ->
                    OutlinedButton(onClick = { onPick(t.id) }, modifier = Modifier.fillMaxWidth()) { Text(t.name) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun MaterialsDialog(ws: WorkbenchState, focus: Focus, onClose: () -> Unit, onAdd: (String) -> Unit, onDeleteSource: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("材料") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ws.materialsOf(focus).forEach { m ->
                    val src = BrainHost.sources.value.refOfMaterial(m.id)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.title.ifBlank { m.kind }, style = MaterialTheme.typography.labelMedium)
                        if (src != null) {
                            Text(
                                (if (src.status == com.moling.core.web.SourceStatus.DELETED) "原文已删除 · " else "") +
                                    (src.url.ifBlank { src.channel.name.lowercase() }),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (src.status == com.moling.core.web.SourceStatus.DELETED) Seal else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            // 顺手项：删除原文入口（Sources.delete：来源保留为 DELETED，材料正文不动）
                            if (src.status != com.moling.core.web.SourceStatus.DELETED) {
                                TextButton(onClick = { onDeleteSource(src.id) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("删除原文", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text(m.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
                    }
                }
                OutlinedTextField(text, { text = it.take(20_000) }, label = { Text("补一点材料") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text.trim()); text = "" }) { Text("补上") } },
        dismissButton = { TextButton(onClick = onClose) { Text("关") } },
    )
}

@Composable
private fun EditDialog(initial: String, onClose: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("改这一段") },
        text = { OutlinedTextField(text, { text = it.take(20_000) }, minLines = 4, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("存") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

@Composable
private fun RewriteDialog(onClose: () -> Unit, onOk: (String) -> Unit) {
    var ask by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("想怎么改") },
        text = { OutlinedTextField(ask, { ask = it.take(200) }, placeholder = { Text("可空：开门见山、更短更有力") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(ask.trim()) }) { Text("改") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

private fun kindLabel(k: ArtifactKind): String = when (k) {
    ArtifactKind.SOURCE_PACK -> "材料包"
    ArtifactKind.OUTLINE -> "纲要"
    ArtifactKind.DRAFT -> "初稿"
    ArtifactKind.COUNCIL_REVIEW -> "四参谋"
    ArtifactKind.DISCOVERY -> "发现"
    ArtifactKind.EXERCISE -> "练习"
    ArtifactKind.ACTOR_RESULT -> "演员结果"
}

private fun hmShort(at: Long): String =
    java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("Asia/Shanghai")).let {
        "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute)
    }
