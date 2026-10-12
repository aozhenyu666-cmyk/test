package com.yishou.app.system

import android.net.Uri
import com.yishou.app.YishouApp
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.yishou.app.deed.Goods
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.MaterialText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 素材从哪来：分享过来的文字、剪贴板、自己写、照片和截图（转写成文字，图片不保存）、让教练写一段。
 * 进来的东西都先存进素材库，练不练、怎么练，由你挑。
 */
class MaterialImport(private val app: YishouApp) {

    private val dao get() = app.database.think()

    suspend fun addText(raw: String, source: String): LlmResult<Material> {
        val text = MaterialText.clean(raw)
        if (!MaterialText.usable(text)) return LlmResult.Err(LlmError.Local("太短了，至少要一两句完整的话"))
        val m = Material(title = MaterialText.title(text), text = text, source = source, createdAt = System.currentTimeMillis())
        return LlmResult.Ok(m.copy(id = dao.insertMaterial(m)))
    }

    /** 图片转写成文字再存 */
    suspend fun addImage(uri: Uri, source: String = MaterialSource.PHOTO): LlmResult<Material> {
        val dataUrl = when (val d = dataUrl(uri)) {
            is LlmResult.Err -> return d
            is LlmResult.Ok -> d.value
        }
        return when (val t = app.vision.transcribeText(dataUrl)) {
            is LlmResult.Err -> t
            is LlmResult.Ok -> addText(t.value, source)
        }
    }

    /** 没东西可练：让教练写一段 */
    suspend fun make(topic: String?): LlmResult<Material> {
        val avoid = dao.recentMaterials(10).filter { it.source == MaterialSource.MADE }.map { it.title }
        return when (val r = app.drillGen.material(topic, avoid)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> {
                val (title, text) = r.value
                val m = Material(title = title.take(16), text = MaterialText.clean(text), source = MaterialSource.MADE, createdAt = System.currentTimeMillis())
                LlmResult.Ok(m.copy(id = dao.insertMaterial(m)))
            }
        }
    }

    suspend fun goods(uri: Uri): LlmResult<List<Goods>> = when (val d = dataUrl(uri)) {
        is LlmResult.Err -> d
        is LlmResult.Ok -> app.vision.readGoods(d.value)
    }

    suspend fun amount(uri: Uri, title: String, unit: String): LlmResult<Pair<Double?, String>> = when (val d = dataUrl(uri)) {
        is LlmResult.Err -> d
        is LlmResult.Ok -> app.vision.readAmount(title, unit, d.value)
    }

    private suspend fun dataUrl(uri: Uri): LlmResult<String> {
        if (!app.settings.vision.value.isComplete) {
            return LlmResult.Err(LlmError.Local("还没有配置识图模型。到 设置 → 识图模型 里填写后才能读图"))
        }
        return try {
            LlmResult.Ok(withContext(Dispatchers.IO) { ImageEncoder.toDataUrl(app, uri) })
        } catch (e: Exception) {
            LlmResult.Err(LlmError.Local("读取图片失败：${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
