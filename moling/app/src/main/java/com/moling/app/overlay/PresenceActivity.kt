package com.moling.app.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.moling.app.BrainHost
import com.moling.app.R
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.PresenceSettings
import com.moling.core.mind.Opening
import com.moling.core.mind.Reply
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** 仅用户点击/回到墨灵才打开。输入用普通 Activity，避免悬浮窗抢焦点。 */
class PresenceActivity : ComponentActivity() {
    companion object {
        fun open(context: Context, mode: String, text: String = "", opening: Opening? = null) {
            runCatching { context.startActivity(Intent(context, PresenceActivity::class.java).putExtra("mode", mode).putExtra("text", text)
                .putExtra("opening", opening?.let { Json.encodeToString(Opening.serializer(), it) }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Presence.error(context, "在你身边页面未打开：${it.javaClass.simpleName}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getStringExtra("mode") == "settings") setTheme(R.style.Theme_Moling)
        super.onCreate(savedInstanceState)
        Presence.init(this)
        val mode = intent.getStringExtra("mode") ?: "settings"
        setContent { MolingTheme {
            when (mode) {
                "settings" -> PresenceSettings { finish() }
                "opening" -> OpeningContent()
                else -> ConclusionContent(mode)
            }
        } }
    }
    @Composable private fun OpeningContent() {
        val op = remember { runCatching { Json.decodeFromString<Opening>(intent.getStringExtra("opening").orEmpty()) }.getOrNull() }
        val clipboard = LocalClipboardManager.current
        if (op == null) { LaunchedEffect(Unit) { finish() }; return }
        AlertDialog(onDismissRequest = { finish() }, title = { Text("开场白") }, text = { Text(op.text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                val launch = packageManager.getLaunchIntentForPackage(op.actor.pkg)
                if (launch == null) Toast.makeText(this, "没装 ${op.actor.label}，可以先复制", Toast.LENGTH_LONG).show()
                else runCatching { val at = System.currentTimeMillis(); startActivity(launch); Presence.startSession(this, op, at); finish() }.onFailure { Presence.error(this, "演员未打开：${it.javaClass.simpleName}") }
            }) { Text("打开 ${op.actor.label}") } },
            dismissButton = { Row { TextButton(onClick = { clipboard.setText(AnnotatedString(op.text)) }) { Text("复制") }; TextButton(onClick = { finish() }) { Text("算了") } } })
    }
    @Composable private fun ConclusionContent(mode: String) {
        val active = remember { Presence.session.value }
        val replyCard = remember { Presence.card.value }
        var text by remember { mutableStateOf(intent.getStringExtra("text").orEmpty()) }
        var confirmClipboard by remember { mutableStateOf(text.isNotBlank()) }
        var busy by remember { mutableStateOf(false) }
        var ingest by remember { mutableStateOf(false) }
        var topic by remember { mutableStateOf(BrainHost.state.value.let { s -> s.atlas.topics.firstOrNull { it.id == s.topicId }?.name.orEmpty() }) }
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it }
        }
        fun submit() {
            if (busy || text.isBlank()) return
            busy = true
            lifecycleScope.launch {
                try {
                    if (mode == "reply" && replyCard != null) Presence.respond(this@PresenceActivity, Reply.Conclusion(replyCard.id, System.currentTimeMillis(), text))
                    else {
                        if (active == null || !Presence.finish(this@PresenceActivity, text, if (confirmClipboard) "复制" else "说", active.opening.sessionId)) {
                            Toast.makeText(this@PresenceActivity, "这次会话已经结束，请重新打开收线", Toast.LENGTH_LONG).show()
                            finish(); return@launch
                        }
                    }
                    if (text.length >= 80 && mode != "reply") ingest = true
                    else { Presence.open(this@PresenceActivity, 1); finish() }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Presence.error(this@PresenceActivity, "收线没接上：${e.javaClass.simpleName}"); Toast.makeText(this@PresenceActivity, "刚才没接上，请再试一次", Toast.LENGTH_LONG).show() }
                finally { busy = false }
            }
        }
        AlertDialog(onDismissRequest = { if (!busy) finish() },
            title = { Text(when { ingest -> "要收进体系图吗？"; confirmClipboard -> "这是刚才聊出来的吗？"; else -> "收线" }) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when {
                        ingest -> { Text(text.take(200)); OutlinedTextField(topic, { topic = it.take(80) }, label = { Text("主题") }) }
                        confirmClipboard -> Text(text.take(200))
                        else -> {
                            OutlinedTextField(text, { text = it.take(12_000) }, label = { Text("一句话：聊出了什么？下一步几点做？") }, minLines = 3)
                            TextButton(onClick = { runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")) }.onFailure { Toast.makeText(this@PresenceActivity, "语音不可用，直接打字吧", Toast.LENGTH_SHORT).show() } }) { Text("麦克风") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                if (ingest) {
                    busy = true
                    lifecycleScope.launch {
                        val n = BrainHost.ingest(text, active?.opening?.actor?.label ?: "收线", topic)
                        Toast.makeText(this@PresenceActivity, "收进 $n 条", Toast.LENGTH_LONG).show()
                        Presence.open(this@PresenceActivity, 1); finish()
                    }
                } else submit()
            }) { Text(if (busy) "在做…" else if (confirmClipboard || ingest) "是" else "交给墨灵") } },
            dismissButton = { TextButton(enabled = !busy, onClick = {
                if (confirmClipboard) { confirmClipboard = false; text = "" }
                else { if (ingest) Presence.open(this@PresenceActivity, 1); finish() }
            }) { Text(if (confirmClipboard) "不是，自己说" else "算了") } })
    }
}
