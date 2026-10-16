package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.agent.Campaign
import com.zongkong.core.work.NoteType

/**
 * 记一下：一句话直接进收集箱，不用想挂哪、不用 AI。
 * 贴一段讨论：在 Gemini、GPT、和人聊过的整段贴进来——原文进收集箱，总控（接了 AI 的话）开一场“消化讨论”：提炼结论、挂到事项上、定下一步。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickScreen(nav: NavHostController, digest: Boolean = false) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(if (digest) null else NoteType.IDEA) }
    var done by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // null = 这是一段讨论
    val isDigest = type == null

    Column(Modifier.fillMaxSize()) {
        BackBar("记一下", { if (!nav.popBackStack()) nav.navigate("home") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(NoteType.IDEA, NoteType.QUESTION, NoteType.MATERIAL).forEach { t ->
                    QuickChip(t.label + if (type == t) " ✓" else "", sig.info) { type = t }
                }
                QuickChip("一段讨论" + if (isDigest) " ✓" else "", sig.think) { type = null }
            }
            Hint(
                if (isDigest) "把在 Gemini、GPT 里聊的（或者和人聊的）整段贴进来。原文完整留在收集箱；接了 AI 的话，总控马上带你消化：提炼结论、挂到事项上、定下一步。"
                else "想到什么写什么，不用想挂在哪——晚上整理收集箱时总控会帮你归位。",
            )
            OutlinedTextField(
                text, { text = it; done = null },
                label = { Text(if (isDigest) "贴进来（可以很长）" else "一句话") },
                minLines = if (isDigest) 8 else 3,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("quick-text"),
            )
            if (isDigest && text.isBlank()) {
                TextButton(onClick = { clip.getText()?.text?.let { text = it } }) { Text("从剪贴板粘贴", color = sig.think) }
            }
            done?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = sig.free) }
            BigButton(if (isDigest) "收进来，让总控消化" else "收进收集箱", {
                val raw = text.trim()
                if (raw.isEmpty()) return@BigButton
                if (isDigest) {
                    val title = "讨论：" + raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(24)
                    val key = app.actions.work.capture(raw, NoteType.MATERIAL, title, "", "", asAction = false)
                    if (app.chat.begin(Campaign.DIGEST, "刚收进来的讨论（记录键 $key）", raw)) {
                        nav.navigate("chat") { popUpTo("quick") { inclusive = true } }
                    } else {
                        done = "原文收进收集箱了。" + if (!app.store.config.value.ai.ready) "没接 AI，晚上整理收集箱时再消化。" else "总控正忙，等会儿在对话里说“消化刚才那段讨论”。"
                        text = ""
                    }
                } else {
                    app.actions.work.capture(raw, type ?: NoteType.IDEA, "", "", "", asAction = false)
                    done = "收好了。再记一条，或者返回。"
                    text = ""
                }
            }, color = if (isDigest) sig.think else sig.info, modifier = Modifier.testTag("quick-save"))
        }
    }
}
