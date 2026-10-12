package com.zongkong.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatMsg

/** 一组显示单元：你的话、参谋的话、一串工具调用、出错。 */
internal sealed interface Item {
    data class User(val m: ChatMsg) : Item
    data class Said(val m: ChatMsg, val options: List<String>) : Item
    data class Steps(val steps: List<ChatMsg>) : Item
    data class Failed(val m: ChatMsg) : Item
}

internal fun group(msgs: List<ChatMsg>): List<Item> {
    val out = mutableListOf<Item>()
    var steps = mutableListOf<ChatMsg>()
    fun flush() {
        if (steps.isNotEmpty()) out += Item.Steps(steps)
        steps = mutableListOf()
    }
    for (m in msgs) {
        when {
            m.role == ChatMsg.TOOL -> steps += m
            m.hidden -> if (m.role == ChatMsg.USER) flush()
            m.role == ChatMsg.USER -> { flush(); out += Item.User(m) }
            m.error -> { flush(); out += Item.Failed(m) }
            m.content.isNotBlank() -> {
                flush()
                val (body, opts) = Agent.options(m.content)
                out += Item.Said(m.copy(content = body), opts)
            }
        }
    }
    flush()
    return out
}

/**
 * 参谋：和 AI 对话，它可以直接改总控（建事项、排行动、开专注锁、改节律、交给一手、打开页面）。
 * 界面参考 Claude：纸色底、你的话在右边的浅色气泡里、参谋的话直接写在纸上（衬线）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val chat = app.chat
    val sig = LocalSignals.current
    val log by chat.log.collectAsStateWithLifecycle()
    val busy by chat.busy.collectAsStateWithLifecycle()
    val route by chat.route.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showMemory by remember { mutableStateOf(false) }
    val items = remember(log.messages) { group(log.messages) }
    val list = rememberLazyListState()

    // 参谋说要打开的页面：等它说完再跳
    LaunchedEffect(route, busy) {
        val r = route
        if (r != null && !busy) {
            chat.route.value = null
            runCatching { nav.navigate(r) }
        }
    }
    LaunchedEffect(items.size, busy) {
        val n = items.size + (if (busy) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    // 别的页面带过来的一句话（快捷按钮、“问参谋”）
    LaunchedEffect(Unit) {
        val pending = app.chatDraft
        if (pending.isNotBlank()) {
            app.chatDraft = ""
            if (config.ai.ready) chat.send(pending) else input = pending
        }
    }

    var image by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) image = saveChatImage(context, uri).orEmpty()
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
                input = if (input.isBlank()) said else "$input $said"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- 顶栏 ----------
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Seal("控", sig.ai, size = 30.dp, filled = true)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("总控", style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
                if (busy) Text("正在处理…", style = MaterialTheme.typography.labelMedium, color = sig.muted, maxLines = 1)
                else StanceLine { nav.navigate("dossier") }
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("···", fontSize = 20.sp, color = sig.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("档案：信用、约定、判定") }, onClick = { menu = false; nav.navigate("dossier") })
                    DropdownMenuItem(text = { Text("它记住的事（${log.memory.size}）") }, onClick = { menu = false; showMemory = true })
                    DropdownMenuItem(text = { Text("清空对话") }, onClick = { menu = false; chat.clear() })
                    DropdownMenuItem(text = { Text("AI 接口设置") }, onClick = { menu = false; nav.navigate("settings/ai") })
                }
            }
        }

        if (!config.ai.ready) {
            Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(24.dp))
                Text("先接上一个 AI", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
                Text(
                    "总控用的是你自己的 AI 接口（DeepSeek、Kimi、通义、OpenAI 都行）。填一次地址、密钥、模型名，以后就能直接跟它说：“帮我把今天排一下”“我卡住了”。",
                    style = MaterialTheme.typography.bodyLarge, color = sig.muted, modifier = Modifier.enter(1),
                )
                Panel(modifier = Modifier.enter(2)) {
                    Text("推荐：DeepSeek", style = MaterialTheme.typography.titleSmall)
                    Hint("地址 https://api.deepseek.com · 模型 deepseek-chat（支持工具调用，便宜）。密钥在 platform.deepseek.com 申请。")
                }
                BigButton("去填 AI 接口", { nav.navigate("settings/ai") }, Modifier.enter(3), color = sig.ai)
            }
            return@Column
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (items.isEmpty()) {
                item { Empty { input = ""; chat.send(it) } }
            }
            itemsIndexed(items, key = { i, it -> i.toString() + when (it) { is Item.User -> it.m.at; is Item.Said -> it.m.at; is Item.Steps -> it.steps.first().at; is Item.Failed -> it.m.at } }) { i, it ->
                Box(Modifier.animateItem()) {
                    when (it) {
                        is Item.User -> UserBubble(it.m.content, it.m.image)
                        is Item.Said -> Said(it.m.content, if (i == items.lastIndex && !busy) it.options else emptyList()) { o -> chat.send(o) }
                        is Item.Steps -> Steps(it.steps)
                        is Item.Failed -> Failed(it.m.content) {
                            log.messages.lastOrNull { m -> m.role == ChatMsg.USER }?.let { u -> chat.send(u.content) }
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 2.dp, top = 4.dp)) {
                        BranchThinking(sig.ai, Modifier.size(28.dp))
                        val last = log.messages.lastOrNull()
                        Text(
                            if (last?.role == ChatMsg.TOOL) last.summary else "在想",
                            style = MaterialTheme.typography.bodySmall, color = sig.muted,
                        )
                    }
                }
            }
        }

        // ---------- 要发的图 ----------
        if (image.isNotBlank()) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val bmp = remember(image) { runCatching { android.graphics.BitmapFactory.decodeFile(image)?.asImageBitmap() }.getOrNull() }
                bmp?.let { androidx.compose.foundation.Image(it, null, modifier = Modifier.size(56.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                Text(if (config.ai.visionModel.isNotBlank()) "用 ${config.ai.visionModel} 看图" else "主模型要能看图（或者在 AI 接口里填识图模型）", style = MaterialTheme.typography.labelSmall, color = sig.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { image = "" }) { Text("×", color = sig.muted) }
            }
        }

        // ---------- 输入栏 ----------
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (input.isEmpty()) Text("跟总控说…", style = MaterialTheme.typography.bodyLarge, color = sig.muted)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(sig.ai),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).testTag("chat-input"),
                    )
                }
                if (!busy) {
                    TextButton(onClick = { pick.launch("image/*") }, modifier = Modifier.testTag("chat-image")) { Text("图", color = sig.muted, style = MaterialTheme.typography.titleMedium) }
                }
                if (input.isBlank() && !busy) {
                    TextButton(onClick = {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        if (i.resolveActivity(context.packageManager) != null) voice.launch(i)
                    }) { Text("说", color = sig.muted, style = MaterialTheme.typography.titleMedium) }
                }
                val canSend = (input.isNotBlank() || image.isNotBlank()) && !busy
                Box(
                    Modifier
                        .size(42.dp)
                        .background(if (busy) MaterialTheme.colorScheme.onSurface else if (canSend) sig.ai else sig.line, CircleShape)
                        .clickable(enabled = busy || canSend) {
                            if (busy) chat.stop() else { chat.send(input, image); input = ""; image = "" }
                        }
                        .testTag("chat-send"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp)))
                    else Text("↑", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
        }
    }

    if (showMemory) {
        AlertDialog(
            onDismissRequest = { showMemory = false },
            title = { Text("总控记住的事") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (log.memory.isEmpty()) Hint("还没有。跟它说你的作息、习惯、偏好，它会记下来，以后每次都带上。")
                    log.memory.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { showMemory = false }) { Text("好") } },
            dismissButton = { if (log.memory.isNotEmpty()) TextButton(onClick = { chat.forgetAll(); showMemory = false }) { Text("全部忘掉", color = sig.strict) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Empty(send: (String) -> Unit) {
    val sig = LocalSignals.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 28.dp)) {
        Text("有什么要我推一把的？", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enter(0))
        Text("直接说事。它会看你的事项、节律和一手，替你建、排、改，做完告诉你做了什么。", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.enter(1))
        listOf(
            "帮我把今天排一下" to "看看手上的事，挑一件，排进时间",
            "我卡住了，帮我拆一步" to "把当前这步拆成 5 分钟能开始的",
            "今天一手练得怎么样" to "读一手的组数，不够就安排",
            "给首页加一个“晚间复盘”按钮" to "让它帮你加功能",
        ).forEachIndexed { i, (q, d) ->
            ChoiceCard(q, d, color = sig.ai, modifier = Modifier.enter(i + 2)) { send(q) }
        }
    }
}

@Composable
internal fun UserBubble(text: String, image: String = "") {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column {
                if (image.isNotBlank()) {
                    val bmp = remember(image) { runCatching { android.graphics.BitmapFactory.decodeFile(image)?.asImageBitmap() }.getOrNull() }
                    bmp?.let { androidx.compose.foundation.Image(it, null, modifier = Modifier.padding(6.dp).widthIn(max = 220.dp).heightIn(max = 220.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                }
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Said(text: String, options: List<String>, onOption: (String) -> Unit) {
    val sig = LocalSignals.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.merge(SerifTitle).copy(fontWeight = FontWeight.Normal, lineHeight = 28.sp),
            modifier = Modifier.testTag("chat-said"),
        )
        if (options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, o ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = sig.ai.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, sig.ai.copy(alpha = 0.5f)),
                        modifier = Modifier.enter(i).clickable { onOption(o) },
                    ) {
                        Text(o, color = sig.ai, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

/** 一串工具调用折成一行：“已执行 3 个操作”，点开看每一步。 */
@Composable
internal fun Steps(steps: List<ChatMsg>) {
    val sig = LocalSignals.current
    var open by remember { mutableStateOf(false) }
    val failed = steps.count { !it.ok }
    Column(
        Modifier
            .fillMaxWidth()
            .background(sig.wash.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (failed > 0) sig.warn else sig.free, CircleShape))
            Text(
                if (steps.size == 1) steps.first().summary.ifBlank { "执行了 1 个操作" } else "已执行 ${steps.size} 个操作" + if (failed > 0) "（$failed 个没成）" else "",
                style = MaterialTheme.typography.labelLarge, color = sig.muted, modifier = Modifier.weight(1f),
            )
            if (steps.size > 1) Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = sig.muted)
        }
        AnimatedVisibility(open && steps.size > 1, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEach { s ->
                    Text((if (s.ok) "✓ " else "✗ ") + s.summary.ifBlank { s.content.take(60) }, style = MaterialTheme.typography.bodySmall, color = if (s.ok) MaterialTheme.colorScheme.onSurface else sig.strict)
                }
            }
        }
    }
}

@Composable
internal fun Failed(text: String, retry: () -> Unit) {
    val sig = LocalSignals.current
    Column(
        Modifier.fillMaxWidth().background(sig.strict.copy(alpha = 0.08f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = sig.strict)
        TextButton(onClick = retry, contentPadding = PaddingValues(0.dp)) { Text("再试一次", color = sig.strict) }
    }
}


/** 选的图复制一份到本机、缩到 1280 以内、存成 JPEG（发给识图模型用）。 */
fun saveChatImage(context: android.content.Context, uri: android.net.Uri): String? = try {
    val dir = java.io.File(context.filesDir, "chat").apply { mkdirs() }
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / sample > 1280) sample *= 2
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }
    // 拍摄时间留着：总控用它判断是不是刚拍的
    val taken = runCatching {
        context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) }
    }.getOrNull()
    if (bmp == null) null else {
        val f = java.io.File(dir, "img_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        if (!taken.isNullOrBlank()) runCatching {
            android.media.ExifInterface(f.absolutePath).apply { setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, taken); saveAttributes() }
        }
        f.absolutePath
    }
} catch (e: Exception) {
    null
}
