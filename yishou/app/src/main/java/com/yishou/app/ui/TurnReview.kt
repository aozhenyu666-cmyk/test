package com.yishou.app.ui

import com.yishou.app.judge.*
import org.json.JSONArray
import org.json.JSONObject

/** 复盘跟断点一起存，退出再来仍得填自己的触发器。 */
data class TurnReview(val q: LockedQ, val answer: String, val trace: ProbeTrace, val verdict: Verdict, val pullBack: Boolean = false) {
    fun json() = JSONObject().put("q", LockedJson.toJson(q)).put("answer", answer).put("pullBack", pullBack)
        .put("shown", trace.shownAt).put("first", trace.firstInputAt).put("submit", trace.submitAt)
        .put("deletes", trace.deletes).put("voice", trace.viaVoice)
        .put("pauses", JSONArray(trace.pauses.map { JSONObject().put("at", it.atChar).put("ms", it.millis) }))
        .put("pasted", JSONArray(trace.pastedRanges.map { JSONObject().put("start", it.first).put("end", it.last) }))
        .put("hits", JSONObject().apply { verdict.hits.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        .put("offTopic", verdict.offTopic).put("passed", verdict.passed).put("note", verdict.note)
    companion object {
        fun parse(o: JSONObject): TurnReview {
            val h = o.getJSONObject("hits")
            val pauses = o.optJSONArray("pauses") ?: JSONArray()
            val pasted = o.optJSONArray("pasted") ?: JSONArray()
            return TurnReview(LockedJson.fromJson(o.getJSONObject("q")), o.getString("answer"),
                ProbeTrace(o.getLong("shown"), if (o.isNull("first")) null else o.getLong("first"), o.getLong("submit"),
                    o.optInt("deletes"), (0 until pauses.length()).map { val p = pauses.getJSONObject(it); Pause(p.getInt("at"), p.getLong("ms")) },
                    (0 until pasted.length()).map { val p = pasted.getJSONObject(it); p.getInt("start")..p.getInt("end") }, o.optBoolean("voice")),
                Verdict(h.keys().asSequence().associateWith { if (h.isNull(it)) null else h.getString(it) }, o.optBoolean("offTopic"), o.getBoolean("passed"), o.optString("note")),
                o.optBoolean("pullBack"))
        }
    }
}
