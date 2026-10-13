package com.yishou.app.system

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.yishou.app.YishouApp
import com.yishou.app.bank.DocText
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 导入题库文件（可以一次选很多个）：
 * - Word（.docx）、文本（.txt / .md / .csv）：本机直接读字，不经过模型；
 * - PDF、图片：一页一页交给识图模型转写（要先配好识图模型）；
 * - 旧版 .doc 读不了，提示另存为 .docx。
 * 读出来的字先在本机拆题；拆不出来、又配了大模型，才让模型整理成题包格式再拆。
 */
class FileImport(private val app: YishouApp) {

    data class FileResult(val name: String, val line: String, val ok: Boolean)

    suspend fun importAll(uris: List<Uri>, progress: (String) -> Unit): List<FileResult> = uris.map { uri ->
        val name = nameOf(uri)
        progress("读 $name ……")
        when (val t = read(uri, name, progress)) {
            is LlmResult.Err -> FileResult(name, t.error.message, false)
            is LlmResult.Ok -> {
                progress("拆题：$name ……")
                var r = app.bankImport.import(t.value)
                if (r.found == 0 && app.settings.llm.value.isComplete) {
                    when (val tidy = app.bankTidy.tidy(t.value) { i, n -> progress("本机没拆出来，请模型整理 $name（$i/$n）……") }) {
                        is LlmResult.Ok -> r = app.bankImport.import(tidy.value)
                        is LlmResult.Err -> return@map FileResult(name, "本机没拆出题，模型整理也失败了：${tidy.error.message}", false)
                    }
                }
                FileResult(name, r.line(), r.total > 0)
            }
        }
    }

    private fun nameOf(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "文件"

    private suspend fun read(uri: Uri, name: String, progress: (String) -> Unit): LlmResult<String> {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = app.contentResolver.getType(uri).orEmpty()
        return try {
            when {
                ext == "docx" || mime.contains("wordprocessingml") -> LlmResult.Ok(withContext(Dispatchers.IO) { docx(uri) })
                ext == "doc" || mime == "application/msword" -> LlmResult.Err(LlmError.Local("旧版 .doc 读不了：在 Word / WPS 里另存为 .docx，或者把文字复制出来点“粘贴题包”"))
                ext == "pdf" || mime == "application/pdf" -> pdf(uri, name, progress)
                mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp") -> image(uri)
                else -> LlmResult.Ok(withContext(Dispatchers.IO) { DocText.decode(bytes(uri)) })
            }
        } catch (e: Exception) {
            LlmResult.Err(LlmError.Local("读不了 $name：${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun bytes(uri: Uri): ByteArray = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)

    private fun docx(uri: Uri): String {
        app.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (e.name == "word/document.xml") return DocText.fromDocumentXml(String(zip.readBytes(), Charsets.UTF_8))
                    e = zip.nextEntry
                }
            }
        }
        error("不是有效的 .docx")
    }

    private suspend fun image(uri: Uri): LlmResult<String> {
        if (!app.settings.vision.value.isComplete) return LlmResult.Err(LlmError.Local("图片要先在 设置 → 识图模型 里配好才能读"))
        val data = withContext(Dispatchers.IO) { ImageEncoder.toDataUrl(app, uri) }
        return app.vision.transcribeText(data)
    }

    /** PDF：一页一页渲染成图，交给识图模型（最多 [MAX_PAGES] 页） */
    private suspend fun pdf(uri: Uri, name: String, progress: (String) -> Unit): LlmResult<String> {
        if (!app.settings.vision.value.isComplete) {
            return LlmResult.Err(LlmError.Local("PDF 要靠识图模型一页页读：先在 设置 → 识图模型 里配好。能复制文字的话，复制出来点“粘贴题包”更快"))
        }
        val pages = withContext(Dispatchers.IO) {
            val fd = app.contentResolver.openFileDescriptor(uri, "r") ?: error("打不开")
            fd.use { PdfRenderer(it).use { r -> (0 until minOf(r.pageCount, MAX_PAGES)).map { i -> render(r, i) } } }
        }
        val sb = StringBuilder()
        pages.forEachIndexed { i, data ->
            progress("读 $name 第 ${i + 1}/${pages.size} 页……")
            when (val t = app.vision.transcribeText(data)) {
                is LlmResult.Ok -> sb.appendLine(t.value)
                is LlmResult.Err -> if (sb.isEmpty()) return t
            }
        }
        return LlmResult.Ok(sb.toString())
    }

    private fun render(r: PdfRenderer, index: Int): String {
        r.openPage(index).use { page ->
            val scale = 1400f / page.width
            val bmp = Bitmap.createBitmap(1400, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    companion object {
        const val MAX_PAGES = 20
    }
}
