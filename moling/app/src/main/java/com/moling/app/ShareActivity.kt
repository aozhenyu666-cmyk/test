package com.moling.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.moling.app.overlay.Presence
import com.moling.app.overlay.PresenceStore
import com.moling.app.ui.MolingTheme
import kotlinx.coroutines.launch

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(50_000).orEmpty() else ""
        if (!PresenceStore(this).enabled("share") || text.isBlank()) {
            Toast.makeText(this, if (text.isBlank()) "没有可分享的文字" else "分享进墨灵已关闭", Toast.LENGTH_SHORT).show(); finish(); return
        }
        setContent { MolingTheme {
            var topic by remember { mutableStateOf(BrainHost.state.value.let { s -> s.atlas.topics.firstOrNull { it.id == s.topicId }?.name.orEmpty() }) }
            var busy by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { if (!busy) finish() }, title = { Text("分享进墨灵") },
                text = { Column { Text(text.take(200)); OutlinedTextField(topic, { topic = it.take(80) }, label = { Text("主题") }); TextButton(enabled = !busy, onClick = { Presence.open(this@ShareActivity, 0, text.take(500)); finish() }) { Text("跟墨灵说") }
                    // 她有问 GPT 的问题还没回答：分享进来的可能就是 GPT 的回答，交给她把关（回答问题、收论断）
                    if (BrainHost.state.value.questions.any { it.answer.isBlank() }) TextButton(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val r = BrainHost.absorbShared(text)
                            Toast.makeText(this@ShareActivity, r, Toast.LENGTH_LONG).show(); finish()
                        }
                    }) { Text("这是 GPT 的回答") } } },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    busy = true
                    lifecycleScope.launch {
                        val n = BrainHost.ingest(text, "分享", topic)
                        Toast.makeText(this@ShareActivity, "收进 $n 条", Toast.LENGTH_LONG).show(); finish()
                    }
                }) { Text(if (busy) "在收…" else "收进体系图") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { finish() }) { Text("算了") } })
        } }
    }
}
