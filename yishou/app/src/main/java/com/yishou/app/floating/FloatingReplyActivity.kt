package com.yishou.app.floating

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.yishou.app.YishouApp
import com.yishou.app.ui.VoiceTextField
import com.yishou.app.ui.theme.YishouTheme
import com.yishou.app.window.WindowViewModel

/** 浮在学习应用上回答；麦克风由这个可见页面按键开启。 */
class FloatingReplyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YishouApp
        val mode = intent.getStringExtra("mode") ?: "window"
        val windowVm = if (mode == "window") ViewModelProvider(app.windowOwner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[WindowViewModel::class.java] else null
        setContent { YishouTheme {
            var text by remember { mutableStateOf("") }
            var note by remember { mutableStateOf<String?>(null) }
            Surface {
                Column(Modifier.widthIn(max = 420.dp).padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("回这一句", style = MaterialTheme.typography.titleLarge)
                    Text(if (mode == "window") windowVm?.overlayPrompt().orEmpty() else intent.getStringExtra("question").orEmpty())
                    if (app.partnerVoice.active) OutlinedTextField(text, { text = it }, label = { Text("写给伙伴") }, modifier = Modifier.fillMaxWidth())
                    else VoiceTextField(text, { text = it }, "可以打字，也可以点麦克风说", Modifier.fillMaxWidth(), minLines = 2)
                    note?.let { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { finish() }) { Text("先想想") }
                        Button(onClick = {
                            when (mode) {
                                "partner" -> if (app.partnerVoice.active) app.partnerVoice.say(text) else { note = "这场已经结束"; return@Button }
                                "scroll" -> com.yishou.app.scroll.ScrollService.replyFromPanel(this@FloatingReplyActivity, text, intent.getLongExtra("quiz", -1))
                                else -> windowVm?.overlayAnswer(text)
                            }
                            finish()
                        }, enabled = text.isNotBlank()) { Text("交这一句") }
                    }
                }
            }
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }
    companion object {
        fun open(context: Context, mode: String, question: String = "", quiz: Long = -1) {
            context.startActivity(Intent(context, FloatingReplyActivity::class.java).putExtra("mode", mode).putExtra("question", question).putExtra("quiz", quiz).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
