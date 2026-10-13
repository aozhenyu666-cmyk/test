package com.yishou.app.bank

import com.yishou.app.card.CardEngine
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialKey
import com.yishou.app.data.MaterialSource
import com.yishou.app.data.ThinkDao
import com.yishou.app.think.DrillKey
import com.yishou.app.think.Lens
import com.yishou.app.think.MaterialText
import com.yishou.app.think.Words

/**
 * 把一份题包收进题库：选择题进题卡库（答案来自题包），申论题进素材库并锁住参考要点。
 * 全在本机做，不调模型。
 */
class BankImport(
    private val engine: CardEngine,
    private val think: ThinkDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val choices: Int, val essays: Int, val found: Int, val noKey: Int) {
        val total: Int get() = choices + essays
        fun line(): String = when {
            found == 0 -> "没认出题目。检查一下格式：每道题要有【题目】，选择题要有 A–D 四个选项，申论题要有【要点】。"
            total == 0 -> "认出 $found 道，都已经在题库里了。"
            else -> buildList {
                if (choices > 0) add("选择题 $choices 道")
                if (essays > 0) add("申论 $essays 道")
            }.joinToString("、", prefix = "收进题库：") +
                (if (found > total) "（${found - total} 道重复，跳过）" else "") +
                (if (noKey > 0) "。$noKey 道没写答案，做完时点一下官方答案" else "")
        }
    }

    suspend fun import(text: String): Result {
        val items = BankParser.parse(text)
        val choices = items.filterIsInstance<BankItem.Choice>()
        val essays = items.filterIsInstance<BankItem.Essay>()
        val added = engine.importBank(choices)
        val seen = think.recentMaterials(2000).filter { it.source == MaterialSource.BANK }.map { Words.norm(it.text) }.toMutableSet()
        var e = 0
        for (it in essays) {
            // 申论题干也常常一样（“根据给定资料，概括……”），连材料一起比
            val whole = Words.norm(buildString {
                if (it.material.isNotBlank()) append(it.material.trim()).append("\n\n")
                append("【题目】").append(it.question.trim())
            }.take(MaterialText.MAX))
            if (whole in seen) continue
            seen += whole
            val text = buildString {
                if (it.material.isNotBlank()) append(it.material.trim()).append("\n\n")
                append("【题目】").append(it.question.trim())
            }.take(MaterialText.MAX)
            val title = (it.type.ifBlank { "申论" } + "：" + MaterialText.title(it.question)).take(20)
            val id = think.insertMaterial(Material(title = title, text = text, source = MaterialSource.BANK, createdAt = clock() + e))
            val key = DrillKey(Lens.ESSAY, it.question.trim(), it.points, value = it.limit?.toDouble(), explain = it.explain)
            think.upsertKey(MaterialKey(id, key.toJson()))
            e++
        }
        return Result(added, e, items.size, choices.count { it.answer == null })
    }
}
