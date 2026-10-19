package com.zongkong.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.zongkong.core.ApiResult
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.vein.Vein
import com.zongkong.core.vein.VeinAi
import com.zongkong.core.vein.VeinMd
import com.zongkong.core.vein.Veins
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 脉络的操作：拆分（AI 提议 → 候选）、推演（打包提问）、导入导出（和「脉络」App、Obsidian 互通）。
 * 用的是总控的 AI 接口。
 */
class VeinActions(private val context: Context, private val store: Store) {
    private val client = ChatClient({ store.config.value.ai })

    private fun date() = store.dateToday().toString()

    fun update(f: (Vein) -> Vein) = store.updateVein(f)

    private suspend fun ask(system: String, user: String): ApiResult<String> =
        when (val r = client.chat(system, listOf(ChatMsg(ChatMsg.USER, user, at = System.currentTimeMillis())), emptyList())) {
            is ApiResult.Ok -> ApiResult.Ok(r.value.content)
            is ApiResult.Err -> r
        }

    /** 拆分一段材料：结果进候选（不直接写入）。返回一句说明。 */
    suspend fun split(system: String, text: String, source: String): String {
        if (text.isBlank()) return "先贴一段材料"
        if (!store.config.value.ai.ready) return "没有配 AI 接口：用“手动新建节点”，或者去 我 → AI 接口"
        val v = store.vein.value
        return when (val r = ask(VeinAi.PROMPT_SPLIT, VeinAi.splitUser(v, system, text))) {
            is ApiResult.Err -> "拆分失败：${r.message}"
            is ApiResult.Ok -> {
                val json = VeinAi.extractJson(r.value) ?: return "模型没有按格式返回 JSON：${r.value.take(80)}"
                val prefix = "c" + (System.currentTimeMillis() / 1000).toString(36)
                val (cands, links) = VeinAi.parseSplit(json, system, source, prefix)
                if (cands.isEmpty()) return "没拆出节点"
                update { it.copy(candidates = it.candidates + cands, candLinks = it.candLinks + links) }
                "拆出 ${cands.size} 张候选卡片、${links.size} 条候选关系，去确认"
            }
        }
    }

    /** 带着打包的子结构提问；返回解析后的回答。 */
    suspend fun think(packText: String): ApiResult<VeinAi.Answer> = when (val r = ask(VeinAi.PROMPT_THINK, packText)) {
        is ApiResult.Err -> r
        is ApiResult.Ok -> ApiResult.Ok(VeinAi.parseAnswer(r.value))
    }

    /** 把“建议新增”放进候选。 */
    fun suggest(nodeId: String, s: List<VeinAi.Suggestion>) {
        val prefix = "q" + (System.currentTimeMillis() / 1000).toString(36)
        update { v ->
            val (c, l) = VeinAi.suggestionsToCandidates(v, nodeId, s, prefix)
            v.copy(candidates = v.candidates + c, candLinks = v.candLinks + l)
        }
    }

    // ---------- 导入导出 ----------

    /** 从选中的文件夹（脉络的“文档/脉络”，或 Obsidian 仓库里的子文件夹）读所有 .md。 */
    fun importTree(tree: Uri): String = try {
        val files = mutableMapOf<String, String>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, prefix: String, depth: Int) {
            if (depth > 5 || files.size > 3000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (name.startsWith(".") && name != ".mailuo") continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$prefix$name/", depth + 1)
                    else if (name.endsWith(".md")) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        context.contentResolver.openInputStream(uri)?.use { files["$prefix$name"] = it.readBytes().decodeToString() }
                    }
                }
            }
        }
        walk(rootId, "", 0)
        var n = 0
        update { v -> VeinMd.import(v, files, date()).also { n = it.second }.first }
        "读了 ${files.size} 个文件，导入 $n 个节点"
    } catch (e: Exception) {
        Log.w(TAG, "导入失败", e)
        "导入失败：${e.message}"
    }

    /** 读脉络导出的 zip。 */
    fun importZip(uri: Uri): String = try {
        val files = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e: ZipEntry? = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".md")) files[e.name.removePrefix("脉络/")] = z.readBytes().decodeToString()
                    e = z.nextEntry
                }
            }
        }
        var n = 0
        update { v -> VeinMd.import(v, files, date()).also { n = it.second }.first }
        "导入 $n 个节点"
    } catch (e: Exception) {
        "导入失败：${e.message}"
    }

    /** 导出成 Obsidian 仓库 zip（和脉络 App 的格式一样）。 */
    fun exportZip(uri: Uri): String = try {
        val files = VeinMd.export(store.vein.value, date())
        context.contentResolver.openOutputStream(uri)?.use { out ->
            ZipOutputStream(out).use { z ->
                files.forEach { (path, text) ->
                    z.putNextEntry(ZipEntry("脉络/$path"))
                    z.write(text.toByteArray())
                    z.closeEntry()
                }
            }
        }
        "导出了 ${files.size} 个文件"
    } catch (e: Exception) {
        "导出失败：${e.message}"
    }

    fun seed(): String {
        update { Veins.seed(it, date()) }
        return "载入了示例体系「思维问题」"
    }

    companion object {
        private const val TAG = "VeinActions"
    }
}
