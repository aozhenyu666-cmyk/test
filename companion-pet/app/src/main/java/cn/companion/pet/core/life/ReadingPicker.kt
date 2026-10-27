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
     * [chosenUrl] 必须与某条 [SearchHit.url] 精确相等（两侧空白不计）；否则一律 [PickOutcome.NoSource]。
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
        return PickOutcome.Picked(activityOf(hit, angle, impression, now))
    }

    /**
     * 构造活动的**唯一通道**，也是「没有来源就不算读过」这条原则的运行期闸门。
     *
     * [OwnActivity] 是公开 data class，别处可以凭空构造一条塞进状态里；只要活动是被本对象挑出来的，
     * 就必然经过这里，无来源（[OwnActivity.hasTraceableSource] 为 false）的条目会当场被 [require] 拦下，
     * 而不是靠调用方的使用顺序「默契地」成立。
     */
    internal fun activityOf(hit: SearchHit, angle: String, impression: String, now: Long): OwnActivity {
        val activity = OwnActivity(
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
        )
        require(activity.hasTraceableSource()) {
            "没有来源 url 的条目不能算「读过」：title=${activity.title}"
        }
        return activity
    }

    /**
     * 用 url 派生稳定 id：同一篇不会因为重复挑选产生两条记录，不同 url 也不会被合并成一条。
     *
     * 直接取 url 本身而不是 `url.hashCode()`：32 位散列在篇数变多后可能让两篇不同的文章撞同一个 id，
     * 从而在状态里互相覆盖 —— 那等于把「它真读过的东西」弄丢，宁可 id 长一点。
     */
    internal fun idOf(url: String): String = "own-" + url.trim()
}
