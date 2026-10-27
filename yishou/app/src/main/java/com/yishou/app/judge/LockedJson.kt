package com.yishou.app.judge

import org.json.JSONArray
import org.json.JSONObject

/** 锁题存库用：出题时就存下来，重启以后断点还是同一道题、同一套要点 */
object LockedJson {

    fun toJson(q: LockedQ): JSONObject = JSONObject()
        .put("id", q.id)
        .put("prompt", q.prompt)
        .put("options", JSONArray(q.options))
        .put("answer", q.answer)
        .put("points", JSONArray(q.points.map { JSONObject().put("id", it.id).put("criterion", it.criterion).put("hint", it.hint) }))
        .put("need", q.need)
        .put("reference", q.reference)
        .put("allowPaste", q.allowPaste)

    fun fromJson(o: JSONObject): LockedQ {
        val opts = o.optJSONArray("options") ?: JSONArray()
        val pts = o.optJSONArray("points") ?: JSONArray()
        return LockedQ(
            id = o.getString("id"),
            prompt = o.getString("prompt"),
            options = (0 until opts.length()).map { opts.getString(it) },
            answer = o.optInt("answer", -1),
            points = (0 until pts.length()).map { i ->
                val p = pts.getJSONObject(i)
                Point(p.getString("id"), p.getString("criterion"), p.optString("hint"))
            },
            need = o.getInt("need"),
            reference = o.optString("reference"),
            allowPaste = o.optBoolean("allowPaste", false),
        )
    }
}
