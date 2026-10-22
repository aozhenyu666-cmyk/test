package com.moling.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
            // F12-7：多个外脑会话在等时，不 Toast 一句就丢，打开选择弹窗并带上原文。
            var pendingText by remember { mutableStateOf<String?>(null) }
            var pendingPicks by remember { mutableStateOf<List<com.moling.core.talk.OutbrainSession>>(emptyList()) }
            var pendingPick by remember { mutableStateOf<String?>(null) }
            fun deliverAsOutbrain(sid: String?) {
                busy = true
                lifecycleScope.launch {
                    when (val r = BrainHost.outbrainReceive(text, sid, source = "share")) {
                        is com.moling.core.workspace.PortResult.Ok -> {
                            val (outcome, artifact) = r.value
                            Toast.makeText(
                                this@ShareActivity,
                                when {
                                    outcome.duplicate -> "这段刚才已经收过了"
                                    artifact != null -> "接回案板了（未采用）"
                                    outcome.history -> "会话结束了，只进历史"
                                    else -> "收下了"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                            if (artifact != null) startActivity(Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_TAB, 3))
                        }
                        is com.moling.core.workspace.PortResult.Failed -> Toast.makeText(this@ShareActivity, r.error.message, Toast.LENGTH_LONG).show()
                    }
                    busy = false
                    finish()
                }
            }
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
                    TextButton(enabled = !busy, onClick = {
                        // F10-7：先建焦点 + 链接材料，马上跳案板；正文在后台抓（可取消），不在这里等
                        busy = true
                        val item = com.moling.core.intake.InhaleParse.fromShare(text, System.currentTimeMillis())
                        lifecycleScope.launch {
                            val f = runCatching { BrainHost.benchStart("", text) }.getOrNull()
                            if (f == null) {
                                Toast.makeText(this@ShareActivity, "没放上去，再试一次", Toast.LENGTH_SHORT).show()
                                busy = false
                                return@launch
                            }
                            if (item.url.isNotBlank()) {
                                runCatching { BrainHost.benchAddMaterial(f.id, item.url) }
                                BrainHost.startBenchFetch(f.id, item.url)
                                Toast.makeText(this@ShareActivity, "放上案板了，正文在后台取", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@ShareActivity, "放上案板了", Toast.LENGTH_SHORT).show()
                            }
                            busy = false
                            startActivity(Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_TAB, 3))
                            finish()
                        }
                    }) { Text("提取正文放上案板") }
                    // 第十二批：外脑会话在等结果时，这段文字可以直接接回案板（未采用）
                    if (com.moling.core.talk.Outbrain.waiting(BrainHost.outbrainState.value).isNotEmpty()) {
                        TextButton(enabled = !busy, onClick = {
                            val waiting = com.moling.core.talk.Outbrain.waiting(BrainHost.outbrainState.value)
                            val tagged = com.moling.core.talk.Outbrain.taggedSessionId(text)
                            if (tagged == null && waiting.size > 1) {
                                // 多会话：先选，不能按时间猜；原文带进弹窗。
                                pendingText = text
                                pendingPicks = waiting
                                pendingPick = null
                            } else {
                                deliverAsOutbrain(tagged ?: waiting.singleOrNull()?.id)
                            }
                        }) { Text("这是外脑带回来的结论") }
                    }
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
            val pickText = pendingText
            if (pickText != null) {
                AlertDialog(
                    onDismissRequest = { if (!busy) pendingText = null },
                    title = { Text("这条结论属于哪个外脑会话？") },
                    text = {
                        Column {
                            Text(pickText.take(200))
                            pendingPicks.forEach { s ->
                                Row {
                                    RadioButton(selected = pendingPick == s.id, onClick = { pendingPick = s.id })
                                    Text(s.id + " · " + s.pack.question.take(16))
                                }
                            }
                            Text("默认不选，不选不能提交；原文里带 [[外脑:ob-…]] 时按标记认。")
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = pendingPick != null, onClick = {
                            val sid = pendingPick
                            pendingText = null
                            pendingPicks = emptyList()
                            pendingPick = null
                            deliverAsOutbrain(sid)
                        }) { Text("接回案板") }
                    },
                    dismissButton = { TextButton(onClick = { pendingText = null; pendingPicks = emptyList() }) { Text("算了") } },
                )
            }
        } }
    }
}
