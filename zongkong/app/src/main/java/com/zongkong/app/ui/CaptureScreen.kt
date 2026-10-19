package com.zongkong.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.work.Capture
import com.zongkong.core.work.Handoff
import com.zongkong.core.work.NoteType
import kotlinx.coroutines.launch

/**
 * 收集：想到什么先收下（原文保留），类型、标题、链接标题、可能相关的事项都自动猜好，点一下就行。
 * 也能说出来（语音）、从别的 App 分享文字 / 链接 / 截图进来。下面是报到和今天收进来的东西。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(nav: NavHostController, presetThread: String = "") {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val day by app.store.today.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val shared by app.shared.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf("") }
    var type by remember { mutableStateOf<NoteType?>(null) }
    var title by remember { mutableStateOf("") }
    var titleTouched by remember { mutableStateOf(false) }
    var thread by rememberSaveable { mutableStateOf(presetThread) }
    var asAction by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var checkin by rememberSaveable { mutableStateOf("") }
    var checkinMsg by remember { mutableStateOf<String?>(null) }

    // 分享进来的内容
    LaunchedEffect(shared) {
        shared?.let { s ->
            if (!Handoff.contains(s.text)) {
                text = s.text; image = s.image
                app.shared.value = null
            }
        }
    }
    val draft = remember(text) { Capture.guess(text) }
    LaunchedEffect(draft) {
        if (!titleTouched) title = draft.title
        // 链接：去抓网页标题
        if (draft.url.isNotBlank() && !titleTouched && draft.title == draft.url.substringAfter("://").substringBefore('/').take(60)) {
            app.actions.work.fetchTitle(draft.url)?.let { if (!titleTouched) title = it }
        }
    }
    val effType = type ?: draft.type
    val suggestions = remember(text, work) { Capture.suggest(work, text) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
                text = if (text.isBlank()) said else text + "\n" + said
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("收集", style = MaterialTheme.typography.headlineSmall.merge(SerifTitle))
        Panel {
            OutlinedTextField(
                text, { text = it }, placeholder = { Text("想到什么、看到什么、卡在哪……先收下") },
                minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            if (image.isNotBlank()) {
                val bmp = remember(image) { runCatching { android.graphics.BitmapFactory.decodeFile(image)?.asImageBitmap() }.getOrNull() }
                bmp?.let { androidx.compose.foundation.Image(it, contentDescription = "截图", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                Hint("截图只存在手机里；Notion 里只记文字和截图的位置。")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    if (i.resolveActivity(context.packageManager) != null) voice.launch(i) else msg = "这台手机没有系统语音识别。可以用输入法上的语音键说。"
                }) { Text("说出来") }
                OutlinedButton(onClick = {
                    val c = Gpt.readClipboard(context)
                    if (Handoff.contains(c)) nav.navigate("import") else if (c.isNotBlank()) text = if (text.isBlank()) c else text + "\n" + c
                }) { Text("粘贴") }
            }
            if (text.isNotBlank() || image.isNotBlank()) {
                Text("自动整理（不对就点一下改）", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(NoteType.IDEA, NoteType.QUESTION, NoteType.MATERIAL).forEach { t ->
                        FilterChip(selected = effType == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                OutlinedTextField(title, { title = it; titleTouched = true }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (draft.url.isNotBlank()) Hint("链接：${draft.url}")
                Text("挂到哪件事上", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val chosen = work.rec(thread)
                    val options = (listOfNotNull(chosen) + suggestions + work.threads().filter { it.threadStatus.open }.sortedByDescending { it.updatedAt }.take(4)).distinctBy { it.key }
                    options.forEach { t ->
                        FilterChip(
                            selected = thread == t.key, onClick = { thread = if (thread == t.key) "" else t.key },
                            label = { Text(t.title.take(14) + if (t in suggestions && t.key != thread) " ·相关" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                    FilterChip(selected = thread.isEmpty(), onClick = { thread = "" }, label = { Text("先不挂") })
                }
                if (thread.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = asAction, onCheckedChange = { asAction = it })
                        Text("同时作为这件事的一个行动（之后再排时间）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = {
                    app.actions.work.capture(text.ifBlank { "（截图）" }, effType, title, draft.url, thread, asAction, image)
                    msg = "收下了" + (work.rec(thread)?.let { "，挂在「${it.title}」" } ?: "") + if (config.notion.workReady) "，会同步到 Notion" else ""
                    text = ""; image = ""; type = null; titleTouched = false; asAction = false
                    if (presetThread.isBlank()) thread = ""
                }, modifier = Modifier.fillMaxWidth()) { Text("收下") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.free) }
        }

        // 报到：重置沉默计时
        Panel {
            Text("报到", style = MaterialTheme.typography.titleSmall)
            Hint(if (config.silenceHours > 0) "每 ${config.silenceHours} 小时至少汇报一次，不然进入严管。" else "沉默检查已关闭。报到仍会记下来。")
            OutlinedTextField(checkin, { checkin = it }, placeholder = { Text("现在在做什么、接下来做什么") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                scope.launch {
                    val err = app.actions.checkin(checkin)
                    checkinMsg = err ?: "已报到"
                    if (err == null) checkin = ""
                }
            }) { Text("报到") }
            checkinMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.muted) }
        }

        // 今天收进来的
        val todayKey = DayClock.dateKey(System.currentTimeMillis(), app.store.zone)
        val todays = work.notes().filter { DayClock.dateKey(it.createdAt, app.store.zone) == todayKey }.sortedByDescending { it.createdAt }
        SectionLabel("今天收进来的 ${todays.size} 条")
        todays.take(20).forEach { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(n.createdAt), style = MaterialTheme.typography.labelSmall.merge(Mono), color = sig.muted, modifier = Modifier.width(40.dp))
                Tag(n[com.zongkong.core.work.F.TYPE], sig.info)
                Text(n.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(work.rec(n.threadKey)?.title?.take(6) ?: "未挂", style = MaterialTheme.typography.labelSmall, color = if (n.threadKey.isBlank()) sig.warn else sig.muted)
            }
        }
        if (day.bounces > 0) Hint("今天被弹回总控 ${day.bounces} 次。")
    }
}

