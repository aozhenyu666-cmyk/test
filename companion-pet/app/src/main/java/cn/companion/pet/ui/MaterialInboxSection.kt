package cn.companion.pet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.MaterialDraftParser
import cn.companion.pet.core.companion.MaterialSaveResult
import cn.companion.pet.core.companion.SharedMaterial
import cn.companion.pet.ui.components.EmptyNote
import cn.companion.pet.ui.theme.LocalCompanionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 应用内手动粘贴入口 + 已保存素材列表。保存是纯本地操作，不调用模型，不消耗预算。
 */
@Composable
fun MaterialInboxSection() {
    val app = PetApp.instance
    var reload by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SharedMaterial>>(emptyList()) }
    var paste by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<MaterialContentType?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        items = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.materialInbox.all() } }
    }

    fun save() {
        val draft = MaterialDraftParser.fromPaste(paste, "manual").copy(
            title = title,
            contentType = type,
        )
        val result = synchronized(app.memoryLock) { app.materialInbox.save(draft) }
        message = when (result) {
            is MaterialSaveResult.Created -> "已收下：" + (result.material.title.ifBlank { "这条素材" })
            is MaterialSaveResult.AlreadySaved -> "已经收过，内容已更新。"
            is MaterialSaveResult.Rejected -> "没有保存：" + result.reason
        }
        if (result !is MaterialSaveResult.Rejected) {
            paste = ""
            title = ""
            type = null
            reload++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("素材收件箱", style = MaterialTheme.typography.titleMedium)
        Text("从 B站、浏览器或阅读器分享文字/链接，或在下面粘贴。只保存你主动给的标题、链接和摘录，不抓网页、不读其他 App 的聊天。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("标题（可选）") }, singleLine = true)
        OutlinedTextField(
            paste, { paste = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
            label = { Text("粘贴链接、摘录或字幕") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeButton("只有链接", type == MaterialContentType.LINK_ONLY) { type = MaterialContentType.LINK_ONLY }
            TypeButton("摘录", type == MaterialContentType.EXCERPT) { type = MaterialContentType.EXCERPT }
            TypeButton("字幕/全文", type == MaterialContentType.TRANSCRIPT) { type = MaterialContentType.TRANSCRIPT }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }) { Text("保存到收件箱") }
            TextButton(onClick = { paste = ""; title = ""; type = null; message = null }) { Text("清空") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (items.isEmpty()) {
            EmptyNote("还没有素材。分享一条链接或摘录试试。")
        } else {
            items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(item.title.ifBlank { item.url ?: "无标题素材" }, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.contentType.name + " · " + date(item.receivedAt) + if (item.url != null) " · " + item.url else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalCompanionColors.current.ink2,
                    )
                    if (item.note.isNotBlank()) Text("附言：" + item.note, style = MaterialTheme.typography.bodySmall)
                    if (item.body.isNotBlank()) Text(item.body.take(120), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        synchronized(app.memoryLock) { app.materialInbox.delete(item.id) }
                        message = "已删除这条素材；来源话题和上下文入口一并失效。"
                        reload++
                    }) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun TypeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

private fun date(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
