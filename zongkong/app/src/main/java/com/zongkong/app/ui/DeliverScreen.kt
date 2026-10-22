package com.zongkong.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.data.Photos
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.work.F
import com.zongkong.core.work.Proof
import com.zongkong.core.work.ProofItem
import com.zongkong.core.work.ProofRound
import com.zongkong.core.work.WorkOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 交付（0.13）：做完一步——交证据（拍照 / 相册 / 链接 / 一句话，有一样就行），总控看一眼给结论。
 *   - 配了 AI：“交给总控看” → 达到 / 部分达到 / 没达到 + 一句理由；不够的话它说一句补什么，补一次，第二轮定结论。
 *     结论不认可以按“达到”记，标“本人改判”。
 *   - 没配 AI，或者点“不用看，自己记”：照旧自评。
 * 结果和以前一样写回行动、事项断点和 Notion；照片只留在手机里。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompleteScreen(key: String, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val items = remember { mutableStateListOf<ProofItem>() }
    val rounds = remember { mutableStateListOf<ProofRound>() }
    var note by remember { mutableStateOf(work.session?.takeIf { it.actionKey == key }?.progress.orEmpty()) }
    var next by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var met by remember { mutableStateOf(WorkOps.Met.YES) }
    var selfMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var savedText by remember { mutableStateOf("") }
    val aiReady = config.ai.ready

    fun add(p: ProofItem?) {
        if (p == null) { msg = "这张图读不了，换一张"; return }
        if (Proof.duplicate(p, app.proofs.usedPhotos())) { msg = "这张照片交过「${app.proofs.usedPhotos()[p.hash]}」了，同一张不能交两件事"; return }
        if (items.any { it.hash.isNotBlank() && it.hash == p.hash }) { msg = "这张已经加过了"; return }
        items += p
        msg = null
    }

    // 分享进来的照片（“交给某一步当证据”）
    LaunchedEffect(Unit) {
        val inbox = app.proofInbox
        app.proofInbox = emptyList()
        inbox.forEach { path -> add(withContext(Dispatchers.IO) { Photos.fromFile(context, File(path)) }) }
    }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) scope.launch { add(withContext(Dispatchers.IO) { Photos.fromFile(context, f, deleteAfter = true) }) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch { add(withContext(Dispatchers.IO) { Photos.fromUri(context, uri) }) }
    }

    fun save(final: WorkOps.Met, source: String) {
        val all = items.toList() + listOfNotNull(link.trim().takeIf { it.isNotBlank() }?.let { ProofItem(ProofItem.LINK, it) })
        app.actions.work.deliver(key, note, all, rounds.toList(), final, source, next)
        com.zongkong.app.system.Notifications.cancelTask(app, key)
        savedText = Proof.resultText(note, all, rounds.toList(), final, source, app.store.zone)
        if (final == WorkOps.Met.NO) nav.navigate("stuck/$key") { popUpTo("complete/$key") { inclusive = true } } else saved = true
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("交付", { nav.popBackStack() })
        if (a == null) { Hint("找不到这一步。", Modifier.padding(16.dp)); return@Column }
        val t = work.rec(a.threadKey)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(a.title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
            if (!saved) {
                Panel {
                    Text("完成依据", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                    Text(a[F.CRITERIA].ifBlank { "（没写：按标题说的事做了没有来看）" }, style = MaterialTheme.typography.bodyLarge)
                }

                // ---------- 证据 ----------
                Panel(modifier = Modifier.testTag("proof-panel")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("证据", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("任选，有一样就行", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        QuickChip("拍照", sig.plan) {
                            val f = Photos.cameraTarget(context)
                            cameraFile = f
                            runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f)) }
                                .onFailure { msg = "打不开相机：${it.message}" }
                        }
                        QuickChip("相册 / 截图", sig.plan) { gallery.launch("image/*") }
                    }
                    if (items.isNotEmpty()) {
                        val flags = remember(items.toList()) { app.actions.work.proofFlags(key, items.toList()) }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items.forEachIndexed { i, p ->
                                ProofThumb(p, flags[i].orEmpty(), Modifier.testTag("proof-photo-$i")) { if (rounds.isEmpty()) items.removeAt(i) }
                            }
                        }
                    }
                    OutlinedTextField(link, { link = it }, label = { Text("链接（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("proof-link"))
                    OutlinedTextField(note, { note = it }, label = { Text("做了什么（一句话也行：数字、产出）") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("result-field"))
                    OutlinedTextField(next, { next = it }, label = { Text("下一步（不知道就空着，交给 GPT）") }, modifier = Modifier.fillMaxWidth().testTag("next-field"))
                }
                msg?.let { Hint(it) }
                val hasAny = items.isNotEmpty() || note.isNotBlank() || link.isNotBlank()

                // ---------- 总控看 ----------
                rounds.lastOrNull()?.let { r -> VerdictCard(r, rounds.size) }
                if (aiReady && !selfMode) {
                    val retry = Proof.canRetry(rounds)
                    if (rounds.isEmpty() || retry) {
                        Button(
                            onClick = {
                                focus.clearFocus()
                                busy = true; msg = null
                                val all = items.toList() + listOfNotNull(link.trim().takeIf { it.isNotBlank() }?.let { ProofItem(ProofItem.LINK, it) }) +
                                    listOfNotNull(note.trim().takeIf { it.isNotBlank() }?.let { ProofItem(ProofItem.TEXT, it) })
                                scope.launch {
                                    when (val r = app.actions.work.review(key, all, rounds.lastOrNull())) {
                                        is ApiResult.Ok -> rounds += r.value
                                        is ApiResult.Err -> msg = "总控这会儿看不了（${r.message}）。可以先自己记。"
                                    }
                                    busy = false
                                }
                            },
                            enabled = hasAny && !busy,
                            modifier = Modifier.fillMaxWidth().testTag("complete-review"),
                        ) { Text(if (busy) "总控在看…" else if (retry) "补好了，再看一次（最后一次）" else "交给总控看") }
                    }
                    rounds.lastOrNull()?.let { r ->
                        val verdict = WorkOps.Met.valueOf(r.met)
                        OutlinedButton(onClick = { save(verdict, "ai") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("complete-save")) {
                            Text("按总控的结论记下（${verdict.label}）")
                        }
                        if (verdict != WorkOps.Met.YES) TextButton(onClick = { save(WorkOps.Met.YES, "override") }, enabled = !busy, modifier = Modifier.testTag("complete-override")) {
                            Text("我不认，按“达到”记（会标“本人改判”）", color = sig.muted)
                        }
                    }
                    if (rounds.isEmpty()) TextButton(onClick = { selfMode = true }, modifier = Modifier.testTag("complete-self")) {
                        Text("不用看，自己记", color = sig.muted)
                    }
                } else {
                    Text("对照完成依据，你自己判：", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkOps.Met.entries.forEach { m -> FilterChip(selected = met == m, onClick = { met = m }, label = { Text(m.label) }) }
                    }
                    Hint(
                        when (met) {
                            WorkOps.Met.YES -> "→ 完成（标“自评”）"
                            WorkOps.Met.PARTIAL -> "→ 待确认：结果先记下，可以带去 GPT 判断够不够"
                            WorkOps.Met.NO -> "→ 卡住：接着问你卡在哪"
                        } + if (!aiReady) "。配了 AI 接口，总控会替你看证据。" else "",
                    )
                    Button(onClick = { save(met, "self") }, enabled = hasAny, modifier = Modifier.fillMaxWidth().testTag("complete-save")) { Text("记下结果") }
                }
            } else {
                Panel(accent = sig.free) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Stamp("完", sig.free, size = 52.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("结果已记下", style = MaterialTheme.typography.titleMedium, color = sig.free)
                            Hint("写进了行动、事项断点和一条“结果”记录，会同步到 Notion；照片留在手机里。GPT 下次读这件事就能看到。")
                        }
                    }
                    Text(savedText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("delivery-summary"))
                }
                Button(onClick = {
                    app.chatDraft = "我刚交付了「${a.title}」。下一步做什么？"
                    nav.navigate("chat") { popUpTo("home") }
                }, modifier = Modifier.fillMaxWidth().testTag("delivery-ask")) { Text("问总控下一步") }
                if (t != null) OutlinedButton(onClick = {
                    app.prompt = app.actions.work.gptPrompt(t.key, "我刚完成了「${a.title}」，结果：${savedText.trim()}。请根据结果判断下一步。")
                    app.promptTitle = t.title
                    app.store.awaitingGpt = t.key
                    nav.navigate("gpt")
                }, modifier = Modifier.fillMaxWidth()) { Text("想深聊：带去 GPT") }
                OutlinedButton(onClick = { nav.navigate("home") { popUpTo("home") { inclusive = true } } }, modifier = Modifier.fillMaxWidth()) { Text("回首页") }
            }
        }
    }
}

@Composable
private fun ProofThumb(p: ProofItem, flags: List<String>, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    val sig = LocalSignals.current
    val bmp = remember(p.value) { runCatching { android.graphics.BitmapFactory.decodeFile(p.value, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
    Column(modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(sig.muted.copy(alpha = 0.12f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("照片", color = sig.muted)
        }
        Text(
            if (p.takenAt > 0) "${Proof.hm(p.takenAt, com.zongkong.core.DayClock.BEIJING)} 拍" else "没有拍摄时间",
            style = MaterialTheme.typography.labelSmall, color = sig.muted,
        )
        flags.filter { !it.startsWith("没有拍摄时间") }.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = sig.warn, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun VerdictCard(r: ProofRound, round: Int) {
    val sig = LocalSignals.current
    val met = WorkOps.Met.valueOf(r.met)
    val color = when (met) {
        WorkOps.Met.YES -> sig.free
        WorkOps.Met.PARTIAL -> sig.warn
        WorkOps.Met.NO -> sig.strict
    }
    Panel(accent = color, modifier = Modifier.testTag("verdict")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stamp(when (met) { WorkOps.Met.YES -> "过"; WorkOps.Met.PARTIAL -> "半"; WorkOps.Met.NO -> "未" }, color, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("总控看过：${met.label}", style = MaterialTheme.typography.titleMedium, color = color)
                Text(r.reason, style = MaterialTheme.typography.bodyMedium)
                if (r.ask.isNotBlank()) Text("补一下就够：${r.ask}", style = MaterialTheme.typography.bodyMedium, color = sig.plan)
                Text("第 $round 轮 / 最多 ${Proof.MAX_ROUNDS} 轮", style = MaterialTheme.typography.labelSmall, color = sig.muted)
            }
        }
    }
}
