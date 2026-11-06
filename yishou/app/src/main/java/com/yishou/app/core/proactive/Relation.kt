package com.yishou.app.core.proactive

/**
 * 关系记忆：不是聊天日志，而是从回答里提炼出来的、带来源和有效期的长期信息。
 *
 * 七类：事实、偏好、承诺、事件、共同经历、纠正、话题。
 * 推断默认低置信；用户明确说的才算事实。
 * 纯逻辑，不碰 Android，可单测。
 */
enum class MemoryType { FACT, PREFERENCE, COMMITMENT, EVENT, SHARED, CORRECTION, TOPIC }

enum class TopicState { OPEN, RESOLVED, PAUSED, CLOSED }

data class RelationMemory(
    val id: Long = 0,
    val type: MemoryType,
    val text: String,
    val source: String = "user",
    val observedAt: Long,
    val validFrom: Long = observedAt,
    /** null = 仍然有效；被纠正时写入关闭时间。 */
    val validTo: Long? = null,
    val confidence: Double = 0.6,
    /** 这条是在纠正哪一条。 */
    val correctsId: Long? = null,
    val topicId: Long? = null,
    val sensitivity: Int = 1,
)

data class Topic(
    val id: Long = 0,
    val title: String,
    val state: TopicState = TopicState.OPEN,
    val createdAt: Long,
    val lastMentionedAt: Long,
    /** 到这个时刻之后可以主动跟进。 */
    val followUpAt: Long,
    val summary: String = "",
)

object RelationRules {
    private val preferenceMarkers = listOf("我喜欢", "我习惯", "我偏好", "我讨厌", "我更喜欢", "我不喜欢")
    private val commitmentMarkers = listOf("我答应", "明天我要", "今晚要", "我会", "计划", "打算", "准备去")
    private val factMarkers = listOf("我在准备", "我最近在", "我正在", "我是", "我住在", "我学", "我的工作是")
    private val eventMarkers = listOf("今天", "昨天", "刚才", "上周") to listOf("做了", "去了", "聊了", "见了")
    private val openTopicMarkers = listOf("卡在", "没搞定", "还没", "正在研究", "在做", "在弄", "还没解决", "搞不定")
    private val resolvedMarkers = listOf("解决了", "做完了", "搞定", "完成了", "弄完了")

    /** 从一句回答里提炼 1–2 条记忆；提不出来就返回空，不硬造。 */
    fun extract(text: String, now: Long, source: String = "user"): List<RelationMemory> {
        val t = text.trim()
        if (t.length < 4) return emptyList()
        val out = mutableListOf<RelationMemory>()
        when {
            preferenceMarkers.any { t.contains(it) } -> out += memory(MemoryType.PREFERENCE, t, now, source)
            commitmentMarkers.any { t.contains(it) } -> out += memory(MemoryType.COMMITMENT, t, now, source)
            factMarkers.any { t.contains(it) } -> out += memory(MemoryType.FACT, t, now, source)
            eventMarkers.first.any { t.contains(it) } && eventMarkers.second.any { t.contains(it) } ->
                out += memory(MemoryType.EVENT, t, now, source)
        }
        if (openTopicMarkers.any { t.contains(it) }) out += memory(MemoryType.TOPIC, t, now, source)
        return out.take(2)
    }

    fun topicFrom(text: String, now: Long): Topic? {
        val t = text.trim()
        if (t.length < 4) return null
        if (isResolved(t)) return null
        if (openTopicMarkers.none { t.contains(it) }) return null
        return Topic(
            title = t.take(40),
            state = TopicState.OPEN,
            createdAt = now,
            lastMentionedAt = now,
            followUpAt = now + 2 * 60 * 60_000L,
            summary = t.take(80),
        )
    }

    fun isResolved(text: String): Boolean {
        // “还没搞定 / 尚未解决”里的“搞定/解决”不是完成，是没完成。
        if (listOf("还没", "没搞定", "没做完", "没完成", "没解决", "尚未", "没有搞定").any { text.contains(it) }) return false
        return resolvedMarkers.any { text.contains(it) }
    }

    fun updateTopicAfterAnswer(topic: Topic, text: String, now: Long, resolved: Boolean): Topic =
        if (resolved) {
            topic.copy(
                state = TopicState.RESOLVED,
                lastMentionedAt = now,
                followUpAt = 0,
                summary = text.take(80),
            )
        } else {
            topic.copy(
                lastMentionedAt = now,
                followUpAt = now + 2 * 60 * 60_000L,
                summary = text.take(80),
            )
        }

    /** 纠正：旧记忆关闭，新记忆指向它。 */
    fun closeOnCorrection(old: RelationMemory, now: Long): RelationMemory = old.copy(validTo = now)

    fun correction(oldId: Long, newText: String, now: Long): RelationMemory = RelationMemory(
        type = MemoryType.CORRECTION,
        text = newText.trim(),
        observedAt = now,
        confidence = 0.9,
        correctsId = oldId,
    )

    /** 只挑当前对话用得上的几条，不把全部记忆塞进提示词。 */
    fun selectForPrompt(
        memories: List<RelationMemory>,
        topics: List<Topic>,
        now: Long,
        limit: Int = 4,
    ): List<RelationMemory> {
        val active = memories.filter { it.validTo == null && it.text.isNotBlank() }
        val priority = mapOf(
            MemoryType.COMMITMENT to 0,
            MemoryType.CORRECTION to 1,
            MemoryType.FACT to 2,
            MemoryType.PREFERENCE to 3,
            MemoryType.EVENT to 4,
            MemoryType.SHARED to 5,
            MemoryType.TOPIC to 6,
        )
        val picked = active.sortedWith(
            compareBy({ priority[it.type] ?: 9 }, { -it.observedAt }),
        ).take(limit).toMutableList()
        // 未解决话题仍是最重要的上下文；把它们的摘要作为记忆补进去。
        topics.filter { it.state == TopicState.OPEN }
            .sortedBy { it.followUpAt }
            .take(2)
            .forEach { topic ->
                if (picked.none { it.text == topic.title }) {
                    picked += RelationMemory(
                        type = MemoryType.TOPIC,
                        text = topic.title,
                        observedAt = topic.lastMentionedAt,
                        topicId = topic.id,
                    )
                }
            }
        return picked.take(limit)
    }

    /** 本地追问：不依赖模型，也要能把上次的话题接回来。 */
    fun topicQuestion(topic: Topic): String = topicQuestion(topic.title)

    fun topicQuestion(title: String): String =
        "上次你说「${title.take(30)}」——现在到哪一步了？"

    private fun memory(type: MemoryType, text: String, now: Long, source: String) = RelationMemory(
        type = type,
        text = text.trim().take(160),
        source = source,
        observedAt = now,
        confidence = 0.6,
    )
}
