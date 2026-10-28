package cn.companion.pet.core.life

import kotlinx.serialization.Serializable

/**
 * 「它自己的生活」里的一件活动：它自己找来读/看的东西，不是用户交代的任务。
 *
 * 只有带可溯源来源（[sourceUrl] 非空）的活动才算真读过；[progress] 与 [totalHint]
 * 只是给界面和提示词用的粗略进度，不是精确页码。
 */
@Serializable
data class OwnActivity(
    val id: String = "",
    val title: String = "",
    val sourceUrl: String = "",
    val sourceSite: String = "",
    val sourceDate: String? = null,
    /** 它自己感兴趣的角度，由挑选素材时写入。 */
    val angleOfInterest: String = "",
    /** 读到目前的印象，一句话，供提示词引用。 */
    val impression: String = "",
    val progress: Int = 0,
    /** 总篇幅的粗略估计；为 null 表示篇幅未知，进度不设上限。 */
    val totalHint: Int? = null,
    val startedAt: Long = 0L,
    val lastTouchedAt: Long = 0L,
    val sourceExcerpt: String = "",
)

/** 它自己此刻的心情。只影响语气，不是对模型真实情感的宣称。 */
@Serializable
enum class OwnMood {
    CALM,
    TIRED,
    CURIOUS,
    DOWN,
    CONTENT,
}

/**
 * 「它自己的生活」的持久状态。所有字段都带默认值，便于旧状态文件反序列化时缺字段也能读。
 */
@Serializable
data class OwnLifeState(
    val activities: List<OwnActivity> = emptyList(),
    val mood: OwnMood = OwnMood.CALM,
    /** 心情变化的理由；心情一变就必须写非空理由。 */
    val moodReason: String = "",
    val moodSince: Long = 0L,
    val lastAdvancedAt: Long = 0L,
)

/**
 * 没有来源链接的活动不能当作“真读过”，否则就会凭空编出读书进度。
 */
fun OwnActivity.hasTraceableSource(): Boolean = sourceUrl.isNotBlank()
