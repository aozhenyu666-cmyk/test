package com.yishou.app.focus

import org.json.JSONArray
import org.json.JSONObject

object FocusJson {
    fun encode(run: FocusRun): JSONObject {
        val s = run.session
        return JSONObject().put("plan", JSONObject().put("task", s.plan.task).put("goal", s.plan.goal)
            .put("totalMin", s.plan.totalMin).put("workMin", s.plan.workMin).put("restMin", s.plan.restMin)
            .put("allowedNames", JSONArray(s.plan.allowedNames)))
            .put("startedAt", s.startedAt).put("endsAt", s.endsAt)
            .put("quitAskedAt", s.quitAskedAt ?: JSONObject.NULL).put("endedAt", s.endedAt ?: JSONObject.NULL)
            .put("retunes", JSONArray(s.retunes.map { JSONObject().put("at", it.at).put("workMin", it.workMin).put("restMin", it.restMin).put("note", it.note) }))
            .put("marks", JSONArray(s.marks.map { JSONObject().put("round", it.round).put("at", it.at).put("done", it.done).put("note", it.note) }))
            .put("allowedPackages", JSONArray(run.allowedPackages.toList())).put("messaging", JSONArray(run.messaging.toList()))
            .put("alertedRounds", JSONArray(run.alertedRounds.toList())).put("endNotified", run.endNotified)
    }

    fun decode(j: JSONObject): FocusRun {
        val p = j.getJSONObject("plan")
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        val plan = FocusPlan(p.getString("task"), p.optString("goal"), p.getInt("totalMin"), p.getInt("workMin"), p.getInt("restMin"), strings(p.optJSONArray("allowedNames")))
        require(FocusPlans.check(plan) == null) { "保存的专注计划不完整" }
        val retunes = j.optJSONArray("retunes") ?: JSONArray()
        val marks = j.optJSONArray("marks") ?: JSONArray()
        val alerts = j.optJSONArray("alertedRounds") ?: JSONArray()
        val s = FocusSession(plan, j.getLong("startedAt"), j.getLong("endsAt"),
            (0 until retunes.length()).map { retunes.getJSONObject(it).let { r -> Retune(r.getLong("at"), r.getInt("workMin"), r.getInt("restMin"), r.optString("note")) } },
            (0 until marks.length()).map { marks.getJSONObject(it).let { m -> RoundMark(m.getInt("round"), m.getLong("at"), m.getBoolean("done"), m.optString("note")) } },
            if (j.isNull("quitAskedAt")) null else j.getLong("quitAskedAt"),
            if (j.isNull("endedAt")) null else j.getLong("endedAt"))
        require(s.endsAt > s.startedAt && s.retunes.all { it.workMin in 3..60 && it.restMin in 0..15 && it.at in s.startedAt..s.endsAt })
        return FocusRun(s, strings(j.optJSONArray("allowedPackages")).toSet(), strings(j.optJSONArray("messaging")).toSet(),
            (0 until alerts.length()).map { alerts.getInt(it) }.toSet(), j.optBoolean("endNotified"))
    }
}
