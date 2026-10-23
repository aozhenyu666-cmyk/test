package cn.companion.pet.core.companion

/**
 * 外部素材的最小抽象。本轮只提供本地 fixture：来源 URL/ID、抓取时间、摘要、有没有字幕。
 * 不在 APK 内登录、抓取或发布；没有真实素材时，提示词里必须明确“没有素材”，
 * 角色不能声称自己刚刚刷到了什么。
 */
data class MaterialItem(
    val sourceUrl: String,
    val sourceId: String,
    val fetchedAt: Long,
    val summary: String,
    val hasSubtitles: Boolean,
    val title: String = "",
) {
    fun render(): String = buildString {
        append("- 来源：").append(sourceUrl).append("（id ").append(sourceId).append("）\n")
        if (title.isNotBlank()) append("- 标题：").append(title).append('\n')
        append("- 抓取时间：").append(InstantText.of(fetchedAt)).append('\n')
        append("- 字幕：").append(if (hasSubtitles) "有" else "没有").append('\n')
        append("- 摘要：").append(summary.trim())
        if (!hasSubtitles) append("\n- 注意：没有字幕，只能依据摘要，不要说看完了视频。")
    }
}

/** 可注入的时间文本，避免在纯 Kotlin 模块里绑定系统时钟格式。 */
object InstantText {
    fun of(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).toString()
}
