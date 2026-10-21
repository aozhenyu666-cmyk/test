package com.moling.app.overlay

/** 系统使用事件的纯计算；屏幕关闭也算离开。离开超过两分钟只计一次。 */
object SessionTiming {
    data class Event(val at: Long, val pkg: String)
    data class Result(val presentMs: Long, val leaves: Int)
    fun measure(actor: String, start: Long, end: Long, events: List<Event>): Result {
        var cursor = start
        var current = ""
        var awayAt = start
        var leaves = 0
        var total = 0L
        var seenActor = false
        for (event in events.filter { it.at in start..end }.sortedBy { it.at } + Event(end, "")) {
            if (current == actor) total += (event.at - cursor).coerceAtLeast(0)
            if (event.pkg == actor && current != actor) {
                if (seenActor && event.at - awayAt > 120_000) leaves++
                seenActor = true
            }
            if (current == actor && event.pkg != actor) awayAt = event.at
            current = event.pkg
            cursor = event.at
        }
        // 末尾的哨兵事件不算真实离开；正在外面也应计一次。
        val last = events.filter { it.at in start..end }.sortedBy { it.at }.lastOrNull()?.pkg
        if (last != actor && seenActor && end - awayAt > 120_000) leaves++
        return Result(total, leaves)
    }
}
