package cn.companion.pet.core.presence

import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility
import cn.companion.pet.core.companion.Topic

/**
 * 当天的短期记忆摘要：从已有的事件日志、待续话题和技能运行记录里挑几行，给模型开口时接上前文。
 * 不另存数据库、不写盘；只读已清洗过的事件文本，遵守遗忘/纠正标记（被删的内容不会通过摘要复活）。
 */
object TodayDigest {
    const val MAX_LINES = 8
    private const val MAX_CHARS = 90

    fun build(
        events: List<CompanionEvent>,
        visibility: EventVisibility,
        today: String,
        topics: List<Topic> = emptyList(),
        /** 今天做过的事（例如“技能「打开番茄钟」成功”），调用方已经脱敏。 */
        done: List<String> = emptyList(),
        forgotten: (String) -> Boolean = { false },
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val visible = events.filter { it.day == today && visibility.allows(it) && it.text.isNotBlank() }
        val lines = mutableListOf<String>()
        visible.filter { it.kind == EventKind.EXCHANGE || it.kind == EventKind.LEGACY_LINE }.takeLast(3).forEach {
            lines += "交流 " + clip(it.text.removePrefix("- "))
        }
        visible.filter { it.kind == EventKind.NOTE }.takeLast(2).forEach { lines += "他记下的念头：" + clip(it.text) }
        topics.filter { !it.dismissed && it.expiresAt > now && it.sharedAt == null }
            .filter { t -> t.sourceIds.none { it in visibility.invalidSourceIds } && !forgotten(t.summary) }
            .take(2).forEach { lines += "还没聊完：" + clip(it.summary) }
        done.takeLast(2).forEach { lines += "今天做过：" + clip(it) }
        return lines.take(MAX_LINES)
    }

    private fun clip(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS) + "…"
    }
}
