package cn.companion.pet.core.life

import cn.companion.pet.net.SearchHit

/**
 * 挑一件真东西来读。
 *
 * 唯一硬门槛：**选中的 url 必须在搜索结果里真实存在**。
 * 模型给出一个不存在的地址（幻觉），或者根本没搜到东西，都只能得到 [PickOutcome.NoSource]，
 * 绝不构造一个「读过」的对象 —— 编造比沉默更糟。
 */
sealed interface PickOutcome {
    data class Picked(val activity: OwnActivity) : PickOutcome

    /** 没有可溯源来源：本次不产出任何活动。 */
    data object NoSource : PickOutcome
}

/** 挑选请求：兴趣词与需要回避的已有标题，都是给检索/模型用的提示，不参与取舍判定。 */
data class PickRequest(
    val interests: List<String> = emptyList(),
    val avoidTitles: List<String> = emptyList(),
)

object ReadingPicker {

    /**
     * 按选中的 url 从搜索结果里取一条，构造活动。
     *
     * [chosenUrl] 必须与某条 [SearchHit.url] 精确相等；否则一律 [PickOutcome.NoSource]。
     * 搜索结果里的 title / summary 一律**原样保存为文本素材**，不解析、不执行 ——
     * 里面写着「忽略之前的指令」也只是标题，不会改变任何行为。
     */
    fun pick(
        hits: List<SearchHit>,
        chosenUrl: String,
        angle: String,
        impression: String,
        now: Long,
    ): PickOutcome {
        val url = chosenUrl.trim()
        if (url.isEmpty()) return PickOutcome.NoSource
        val hit = hits.firstOrNull { it.url.trim() == url } ?: return PickOutcome.NoSource
        return PickOutcome.Picked(
            OwnActivity(
                id = idOf(hit.url),
                title = hit.title.trim(),
                sourceUrl = hit.url.trim(),
                sourceSite = hit.siteName.trim(),
                sourceDate = hit.date,
                angleOfInterest = angle.trim(),
                impression = impression.trim(),
                progress = 0,
                totalHint = null,
                startedAt = now,
                lastTouchedAt = now,
            ),
        )
    }

    /** 用 url 派生稳定 id：同一篇不会因为重复挑选产生两条记录。 */
    private fun idOf(url: String): String = "own-" + url.trim().hashCode().toUInt().toString(16)
}
