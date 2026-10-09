package com.yishou.app.stats

import org.json.JSONObject

/** 一类请求一天的用量。 */
data class KindStats(
    val calls: Int = 0,
    val fails: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalMillis: Long = 0,
    /** 请求文字的总字数，用来看上下文有没有越来越长 */
    val requestChars: Long = 0,
) {
    val tokens: Long get() = promptTokens + completionTokens
    val avgMillis: Long get() = if (calls == 0) 0 else totalMillis / calls
    val avgRequestChars: Long get() = if (calls == 0) 0 else requestChars / calls

    operator fun plus(o: KindStats) = KindStats(
        calls + o.calls, fails + o.fails, promptTokens + o.promptTokens,
        completionTokens + o.completionTokens, totalMillis + o.totalMillis, requestChars + o.requestChars,
    )
}

/** 一天的盘点：每类请求的用量，以及几个计数事件。 */
data class DayStats(
    val date: String,
    val kinds: Map<String, KindStats> = emptyMap(),
    val events: Map<String, Int> = emptyMap(),
) {
    val total: KindStats get() = kinds.values.fold(KindStats()) { a, b -> a + b }

    fun plusUsage(kind: String, ok: Boolean, prompt: Int, completion: Int, millis: Long, chars: Int): DayStats {
        val add = KindStats(1, if (ok) 0 else 1, prompt.toLong(), completion.toLong(), millis, chars.toLong())
        return copy(kinds = kinds + (kind to ((kinds[kind] ?: KindStats()) + add)))
    }

    fun plusEvent(name: String): DayStats = copy(events = events + (name to ((events[name] ?: 0) + 1)))

    fun event(name: String): Int = events[name] ?: 0

    fun toJson(): String = JSONObject()
        .put("date", date)
        .put("kinds", JSONObject().apply {
            kinds.forEach { (k, v) ->
                put(k, JSONObject()
                    .put("calls", v.calls).put("fails", v.fails)
                    .put("prompt", v.promptTokens).put("completion", v.completionTokens)
                    .put("ms", v.totalMillis).put("chars", v.requestChars))
            }
        })
        .put("events", JSONObject().apply { events.forEach { (k, v) -> put(k, v) } })
        .toString()

    companion object {
        const val GATE_SHOWN = "gate_shown"
        const val GATE_PASS = "gate_pass"
        const val OFFLINE_PASS = "offline_pass"
        const val WINDOW_BLOCK = "window_block"
        const val LOOK = "look"

        fun fromJson(text: String?, date: String): DayStats {
            if (text.isNullOrBlank()) return DayStats(date)
            return try {
                val o = JSONObject(text)
                val k = o.optJSONObject("kinds")
                val kinds = k?.keys()?.asSequence()?.associateWith { key ->
                    val v = k.getJSONObject(key)
                    KindStats(
                        v.optInt("calls"), v.optInt("fails"), v.optLong("prompt"),
                        v.optLong("completion"), v.optLong("ms"), v.optLong("chars"),
                    )
                } ?: emptyMap()
                val e = o.optJSONObject("events")
                val events = e?.keys()?.asSequence()?.associateWith { e.optInt(it) } ?: emptyMap()
                DayStats(o.optString("date", date), kinds, events)
            } catch (ex: Exception) {
                DayStats(date)
            }
        }
    }
}
