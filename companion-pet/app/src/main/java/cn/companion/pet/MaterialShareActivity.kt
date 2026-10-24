package cn.companion.pet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
            val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val title = incoming.getStringExtra(Intent.EXTRA_TITLE)
                ?: incoming.getStringExtra(Intent.EXTRA_SUBJECT)
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
    private fun ShareScreen(initial: MaterialDraft, error: String?) {
        val app = PetApp.instance
        var title by remember { mutableStateOf(initial.title) }
        var url by remember { mutableStateOf(initial.url.orEmpty()) }
        var body by remember { mutableStateOf(initial.body) }
        var note by remember { mutableStateOf(initial.note) }
        var type by remember { mutableStateOf<MaterialContentType?>(null) }
        var result by remember { mutableStateOf<String?>(error) }
        var saved by remember { mutableStateOf(false) }

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
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题（可选）") }, singleLine = true)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("链接（可选）") }, singleLine = true)
                OutlinedTextField(
                    body, { body = it }, Modifier.fillMaxWidth(), minLines = 6,
                    label = { Text(if (body.isBlank()) "摘录 / 字幕（没有正文就留空）" else "摘录 / 字幕") },
                )
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("你的附言（可选）") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeChip("只有链接", type == MaterialContentType.LINK_ONLY || (type == null && body.isBlank())) {
                        type = MaterialContentType.LINK_ONLY
                    }
                    TypeChip("摘录", type == MaterialContentType.EXCERPT || (type == null && body.isNotBlank())) {
                        type = MaterialContentType.EXCERPT
                    }
                    TypeChip("字幕/全文", type == MaterialContentType.TRANSCRIPT) {
                        type = MaterialContentType.TRANSCRIPT
                    }
                }
                if (type == MaterialContentType.LINK_ONLY && body.isNotBlank()) {
                    Text("选了“只有链接”时不会保存正文；要保存正文请选“摘录”。", style = MaterialTheme.typography.bodySmall)
                }
                CutAction(
                    text = if (saved) "完成" else "保存这份材料",
                    highlighted = true,
                    enabled = !saved,
                    onClick = {
                        val d = MaterialDraft(
                            url = url.takeIf { it.isNotBlank() },
                            title = title,
                            body = body,
                            note = note,
                            contentType = type,
                            source = "share",
                        )
                        when (val r = app.materialInbox.save(d)) {
                            is MaterialSaveResult.Created -> {
                                val name = r.material.title.ifBlank { r.material.url ?: "这条素材" }
                                result = "已收下：" + name
                                saved = true
                            }
                            is MaterialSaveResult.AlreadySaved -> {
                                result = "已经收过这条素材，内容已更新。"
                                saved = true
                            }
                            is MaterialSaveResult.Rejected -> result = "没有保存：" + r.reason
                        }
                    },
                )
                TextButton(onClick = { finish() }) { Text(if (saved) "完成" else "取消") }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    @Composable
    private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
        if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
    }
}
