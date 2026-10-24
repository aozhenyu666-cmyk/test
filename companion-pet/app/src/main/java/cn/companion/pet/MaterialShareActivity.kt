package cn.companion.pet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.MaterialDraft
import cn.companion.pet.core.companion.MaterialDraftParser
import cn.companion.pet.core.companion.MaterialSaveResult
import cn.companion.pet.ui.components.CutAction
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.CompanionTheme

/**
 * ACTION_SEND text/plain 的接收页：只预览、编辑、保存用户主动分享的标题/链接/摘录。
 * 不登录账号、不抓网页、不读 Cookie，也不在这里调用模型。
 */
class MaterialShareActivity : ComponentActivity() {

    private var draft: MaterialDraft = MaterialDraft()
    private var invalid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        if (incoming?.action != Intent.ACTION_SEND) {
            invalid = "只支持分享文字或链接。"
        } else {
            val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val title = incoming.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()
                ?: incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
            if (text.isBlank()) invalid = "这次分享没有文字内容。" else {
                draft = MaterialDraftParser.fromShare(text, title, "share")
            }
        }
        setContent {
            CompanionTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ShareScreen(draft, invalid)
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ShareScreen(initial: MaterialDraft, error: String?) {
        val app = PetApp.instance
        var title by rememberSaveable { mutableStateOf(initial.title) }
        var url by rememberSaveable { mutableStateOf(initial.url.orEmpty()) }
        var body by rememberSaveable { mutableStateOf(initial.body) }
        var note by rememberSaveable { mutableStateOf(initial.note) }
        var type by remember { mutableStateOf<MaterialContentType?>(null) }
        var result by remember { mutableStateOf<String?>(error) }
        var saved by rememberSaveable { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Scaffold { inner ->
            Column(
                Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                    .padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PageHeader("收下这份材料", "先确认内容，再保存。只存你分享的标题、链接和摘录，不抓网页。")
                if (initial.url == null && initial.body.isBlank()) {
                    Text("这次分享没有可识别的内容。", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题（可选）") }, singleLine = true, enabled = !saving && !saved)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("链接（可选）") }, singleLine = true, enabled = !saving && !saved)
                OutlinedTextField(
                    body, { body = it }, Modifier.fillMaxWidth(), minLines = 6, enabled = !saving && !saved,
                    label = { Text(if (body.isBlank()) "摘录 / 字幕（没有正文就留空）" else "摘录 / 字幕") },
                )
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("你的附言（可选）") }, enabled = !saving && !saved)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("只有链接", type == MaterialContentType.LINK_ONLY || (type == null && body.isBlank()), enabled = !saving && !saved) {
                        type = MaterialContentType.LINK_ONLY
                    }
                    TypeChip("摘录", type == MaterialContentType.EXCERPT || (type == null && body.isNotBlank()), enabled = !saving && !saved) {
                        type = MaterialContentType.EXCERPT
                    }
                    TypeChip("字幕/全文", type == MaterialContentType.TRANSCRIPT, enabled = !saving && !saved) {
                        type = MaterialContentType.TRANSCRIPT
                    }
                }
                if (type == MaterialContentType.LINK_ONLY && body.isNotBlank()) {
                    Text("选了“只有链接”且带正文时会拒绝保存；要保存正文请选“摘录”。", style = MaterialTheme.typography.bodySmall)
                }
                CutAction(
                    text = if (saved) "完成" else "保存这份材料",
                    highlighted = true,
                    enabled = !saved && !saving,
                    onClick = {
                        val d = MaterialDraft(
                            url = url.takeIf { it.isNotBlank() },
                            title = title,
                            body = body,
                            note = note,
                            contentType = type,
                            source = "share",
                        )
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    val outcome = withContext(Dispatchers.IO) { runCatching { app.materialInbox.save(d) } }
                                    when (val r = outcome.getOrNull()) {
                                        is MaterialSaveResult.Created -> { result = "已收下：" + r.material.title.ifBlank { r.material.url ?: "这条素材" }; saved = true }
                                        is MaterialSaveResult.AlreadySaved -> { result = "已经收过这条素材，内容已更新。"; saved = true }
                                        is MaterialSaveResult.Rejected -> result = "没有保存：" + r.reason
                                        null -> result = "没有保存成功，原内容已保留，请重试。"
                                    }
                                } finally { saving = false }
                            }
                        }
                    },
                )
                TextButton(onClick = { finish() }) { Text(if (saved) "完成" else "取消") }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    @Composable
    private fun TypeChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        if (selected) Button(enabled = enabled, onClick = onClick) { Text(label) } else OutlinedButton(enabled = enabled, onClick = onClick) { Text(label) }
    }
}
