package com.dshdriver.core

/** Persisted wall-clock deadlines; a delayed tick produces one observation, never a burst. */
data class Supervision(
    val startedAt: Long, val endsAt: Long, val nextMicro: Long, val nextDeep: Long,
    val windowFrom: Long, val active: Boolean = true,
) {
    data class Due(val kind: String, val from: Long, val to: Long, val state: Supervision)
    fun poll(now: Long): Due? {
        if (!active || now < startedAt || now < minOf(nextMicro, nextDeep)) return null
        val to = minOf(now, endsAt)
        if (to <= windowFrom) return null
        val deep = nextDeep <= to
        if (!deep && nextMicro > to) return null
        fun next(step: Long) = startedAt + ((to - startedAt) / step + 1) * step
        return Due(if (deep) "deep" else "micro", windowFrom, to,
            copy(nextMicro = next(5 * 60_000L), nextDeep = next(10 * 60_000L), windowFrom = to, active = to < endsAt))
    }
    companion object {
        fun start(now: Long, minutes: Int) = Supervision(now, now + minutes.coerceIn(10, 240) * 60_000L,
            now + 5 * 60_000L, now + 10 * 60_000L, now)
    }
}

/** null closes attribution during screen-off, disconnection or a process gap. */
object AppTimeline {
    data class Event(val at: Long, val pkg: String?)
    data class Usage(val pkg: String, val ms: Long)
    data class Summary(val apps: List<Usage>, val unknownMs: Long)
    fun retain(events: List<Event>, since: Long): List<Event> {
        val sorted = events.sortedBy { it.at }
        return listOfNotNull(sorted.lastOrNull { it.at <= since }) + sorted.filter { it.at > since }
    }
    fun summarize(events: List<Event>, from: Long, to: Long): Summary {
        if (to <= from) return Summary(emptyList(), 0)
        val selected = retain(events.filter { it.at <= to }, from)
        var cursor = from
        var pkg: String? = null
        val totals = linkedMapOf<String, Long>()
        var unknown = 0L
        fun add(until: Long) {
            val delta = (until - cursor).coerceAtLeast(0)
            val key = pkg
            if (key == null) unknown += delta else totals[key] = (totals[key] ?: 0) + delta
            cursor = until
        }
        for (event in selected) {
            if (event.at <= from) { pkg = event.pkg; continue }
            add(event.at); pkg = event.pkg
        }
        add(to)
        return Summary(totals.map { Usage(it.key, it.value) }.sortedByDescending { it.ms }, unknown)
    }
}

object Checkins {
    val KEYS = listOf("did", "drift", "why", "next")
    fun fixed(kind: String) = if (kind == "deep") listOf(
        "刚才做了什么？", "有没有偏离你的目标？", "为什么会这样？", "接下来准备做什么？",
    ) else listOf("刚才 5 分钟你在做什么？")
}
