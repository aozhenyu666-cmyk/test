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
                    // I9：直接放上案板（不自动开工，进案板后他自己点“替我先做一块”）
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val f = runCatching { BrainHost.benchStart("", text) }.getOrNull()
                            Toast.makeText(this@ShareActivity, if (f == null) "没放上去，再试一次" else "放上案板了", Toast.LENGTH_SHORT).show()
                            busy = false
                            if (f != null) {
                                startActivity(Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_TAB, 3))
                                finish()
                            }
                        }
                    }) { Text("放上案板") }
                    // 别的 AI 的回答（交接包发出去后回来的）：交给她把关——回答问题、收论断、改“了解”要你点头
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val r = BrainHost.absorbShared(text)
                            Toast.makeText(this@ShareActivity, r, Toast.LENGTH_LONG).show(); finish()
                        }
                    }) { Text("这是 AI 的回答（交给她把关）") } } },
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
