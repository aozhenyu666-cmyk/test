package com.moling.app.mirror

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.moling.core.mirror.InboxItem
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * 读 ChatGPT 的“导出数据”（设置 → 数据控制 → 导出，邮件里那个 zip，或解压出来的 conversations.json）。
 *
 * 文件可能很大，所以边读边丢：只留 [since]（秒）之后更新过的、最新的 [max] 段对话，
 * 每段整理成“他 / GPT”的来回（太长就留开头和结尾），交给大脑当收件箱的材料把关。
 * 只在他自己选了文件时读；不联网，不上传。
 */
object GptImport {
    data class Conversation(val id: String, val title: String, val updated: Double, val text: String)

    fun read(context: Context, uri: Uri, since: Double, max: Int = 20): List<Conversation> {
        val input = context.contentResolver.openInputStream(uri) ?: error("打不开这个文件")
        input.use { raw ->
            val buffered = raw.buffered()
            buffered.mark(4)
            val head = ByteArray(2).also { buffered.read(it) }
            buffered.reset()
            val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            if (!isZip) return parse(buffered, since, max)
            val zip = ZipInputStream(buffered)
            val found = mutableListOf<Conversation>()
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && name.startsWith("conversations") && name.endsWith(".json")) {
                    found += parse(NoClose(zip), since, max)
                }
            }
            if (found.isEmpty()) error("压缩包里没找到 conversations.json")
            return found.sortedByDescending { it.updated }.take(max)
        }
    }

    fun items(list: List<Conversation>): List<InboxItem> =
        list.map { InboxItem("gpt:${it.id}@${it.updated.toLong()}", "《${it.title}》\n${it.text}", "ChatGPT 对话") }

    private fun parse(input: InputStream, since: Double, max: Int): List<Conversation> {
        val keep = mutableListOf<Conversation>()
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        r.isLenient = true
        r.beginArray()
        while (r.hasNext()) {
            val c = conversation(r) ?: continue
            if (c.updated <= since || c.text.isBlank()) continue
            keep += c
            // 只留最新的 max 段，内存不涨
            if (keep.size > max * 2) {
                keep.sortByDescending { it.updated }
                while (keep.size > max) keep.removeAt(keep.lastIndex)
            }
        }
        return keep.sortedByDescending { it.updated }.take(max)
    }

    private data class Msg(val at: Double, val role: String, val text: String)

    private fun conversation(r: JsonReader): Conversation? {
        var id = ""
        var title = ""
        var updated = 0.0
        val msgs = mutableListOf<Msg>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id", "conversation_id" -> id = str(r).ifBlank { id }
                "title" -> title = str(r)
                "update_time" -> updated = num(r)
                "mapping" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        node(r)?.let { msgs += it }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (msgs.isEmpty()) return null
        val lines = msgs.sortedBy { it.at }.map { (if (it.role == "user") "他：" else "GPT：") + it.text.trim().take(1500) }
        val all = lines.joinToString("\n")
        // 太长：留开头（他问了什么）和结尾（聊到哪了）
        val text = if (all.length <= 2400) all else all.take(900) + "\n……\n" + all.takeLast(1450)
        return Conversation(id.ifBlank { title + updated }, title.ifBlank { "没有标题" }, updated, text)
    }

    /** mapping 里的一个节点：只要他和 GPT 说的文字。 */
    private fun node(r: JsonReader): Msg? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var msg: Msg? = null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "message" && r.peek() == JsonToken.BEGIN_OBJECT) msg = message(r) else r.skipValue()
        }
        r.endObject()
        return msg
    }

    private fun message(r: JsonReader): Msg? {
        var role = ""
        var at = 0.0
        val parts = StringBuilder()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "author" -> {
                    r.beginObject()
                    while (r.hasNext()) if (r.nextName() == "role") role = str(r) else r.skipValue()
                    r.endObject()
                }
                "create_time" -> at = num(r)
                "content" -> {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "parts" && r.peek() == JsonToken.BEGIN_ARRAY) {
                            r.beginArray()
                            while (r.hasNext()) {
                                if (r.peek() == JsonToken.STRING) parts.append(r.nextString()).append('\n') else r.skipValue()
                            }
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (role != "user" && role != "assistant") return null
        val t = parts.toString().trim()
        return if (t.isBlank()) null else Msg(at, role, t)
    }

    private fun str(r: JsonReader): String =
        if (r.peek() == JsonToken.STRING) r.nextString() else { r.skipValue(); "" }

    private fun num(r: JsonReader): Double =
        if (r.peek() == JsonToken.NUMBER || r.peek() == JsonToken.STRING) r.nextString().toDoubleOrNull() ?: 0.0 else { r.skipValue(); 0.0 }

    /** JsonReader 读完会关流；压缩包里还有别的文件，不能让它把整个 zip 关掉。 */
    private class NoClose(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {}
    }
}
