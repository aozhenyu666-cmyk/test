package cn.companion.pet.core.partner

import cn.companion.pet.core.life.OwnActivity

/** Search snippets are finite observations, never evidence of having read the linked article. */
object ReadingEvidence {
    fun blocksNextSearch(activity: OwnActivity, now: Long): Boolean = activity.totalHint?.let {
        activity.progress < it
    } ?: (now - activity.startedAt < 45 * 60_000L)

    fun anchor(activity: OwnActivity): String = buildString {
        append("检索到《").append(activity.title).append("》的搜索摘要，尚未读取文章正文。")
        if (activity.sourceExcerpt.isNotBlank()) append("\n摘要原文：").append(activity.sourceExcerpt.take(400))
        append("\n来源：").append(activity.sourceUrl)
    }
}
