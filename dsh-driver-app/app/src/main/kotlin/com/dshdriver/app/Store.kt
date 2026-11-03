package com.dshdriver.app

import android.content.Context
import com.dshdriver.core.Agenda
import com.dshdriver.core.DieStat
import com.dshdriver.core.History
import com.dshdriver.core.Profile
import com.dshdriver.core.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机存储，全在 APP 私有目录 files/driver/ 下：
 *   settings.json  允许的应用、默认时长
 *   profile.json   你自己写的主线目标和惯性死穴
 *   history.json   跨会话统计（骰子推进率、切屏分钟、回复耗时）
 *   agenda.json    思考议程（没接上的断点、没补上的依据、没把握的结论）
 *   last.json      上一次锁定的结果
 *   sessions/<id>.jsonl  每次锁定的推进日志，一行一条
 * 写入先写 .tmp 再改名，写一半断电不会把旧文件写坏。
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "driver").apply { mkdirs() }

    data class Settings(
        val anchors: List<String> = DEFAULT_ANCHORS,
        val extraAllowed: List<String> = listOf("io.github.ccch1mneyyy.dsh_app"),
        val minutes: Int = 15,
    )

    data class Last(val endedAt: Long, val reason: String, val conclusion: String?, val summary: Telemetry.Summary, val topic: String, val task: String)

    private fun read(name: String): JSONObject? = runCatching { JSONObject(File(dir, name).readText()) }.getOrNull()
    private fun readArray(name: String): JSONArray? = runCatching { JSONArray(File(dir, name).readText()) }.getOrNull()

    private fun write(name: String, text: String) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ── 设置 ──
    fun settings(): Settings {
        val o = read("settings.json") ?: return Settings()
        return Settings(
            anchors = o.optJSONArray("anchors").strings().ifEmpty { DEFAULT_ANCHORS },
            extraAllowed = o.optJSONArray("extraAllowed").strings(),
            minutes = o.optInt("minutes", 15).coerceIn(1, 60),
        )
    }

    fun saveSettings(s: Settings) = write("settings.json", JSONObject()
        .put("anchors", JSONArray(s.anchors)).put("extraAllowed", JSONArray(s.extraAllowed)).put("minutes", s.minutes).toString(2))

    // ── 主线与死穴 ──
    fun profile(): Profile {
        val o = read("profile.json") ?: return Profile()
        return Profile(o.optJSONArray("goals").strings(), o.optJSONArray("habits").strings())
    }

    fun saveProfile(p: Profile) = write("profile.json", JSONObject().put("goals", JSONArray(p.goals)).put("habits", JSONArray(p.habits)).toString(2))

    // ── 统计 ──
    fun history(): History {
        val o = read("history.json") ?: return History()
        val h = History(sessions = o.optInt("sessions", 0))
        o.optJSONObject("dice")?.let { d -> d.keys().forEach { k -> d.optJSONObject(k)?.let { h.dice[k] = DieStat(it.optInt("tries"), it.optInt("advanced")) } } }
        o.optJSONObject("escapeMinutes")?.let { e -> e.keys().forEach { k -> k.toIntOrNull()?.let { h.escapeMinutes[it] = e.optInt(k) } } }
        o.optJSONArray("latencies")?.let { a -> for (i in 0 until a.length()) h.latencies += a.optLong(i) }
        return h
    }

    fun saveHistory(h: History) {
        val dice = JSONObject().apply { h.dice.forEach { (k, v) -> put(k, JSONObject().put("tries", v.tries).put("advanced", v.advanced)) } }
        val esc = JSONObject().apply { h.escapeMinutes.forEach { (k, v) -> put(k.toString(), v) } }
        write("history.json", JSONObject().put("sessions", h.sessions).put("dice", dice).put("escapeMinutes", esc)
            .put("latencies", JSONArray(h.latencies.takeLast(50))).toString(2))
    }

    // ── 议程 ──
    fun agenda(): Agenda {
        val a = readArray("agenda.json") ?: return Agenda()
        val items = (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Agenda.Item(
                    id = o.getString("id"), text = o.getString("text"), anchor = o.getString("anchor"), source = o.getString("source"),
                    quote = o.optStringOrNull("quote"), missing = o.optStringOrNull("missing"),
                    createdAt = o.optLong("createdAt"), lastSeenAt = o.optLong("lastSeenAt"), touches = o.optInt("touches", 1),
                    skips = o.optInt("skips"), status = o.optString("status", "open"), closedAt = if (o.has("closedAt")) o.optLong("closedAt") else null,
                )
            }.getOrNull()
        }
        return Agenda(items)
    }

    fun saveAgenda(a: Agenda) = write("agenda.json", JSONArray(a.items.map { x ->
        JSONObject().put("id", x.id).put("text", x.text).put("anchor", x.anchor).put("source", x.source)
            .putOpt("quote", x.quote).putOpt("missing", x.missing).put("createdAt", x.createdAt).put("lastSeenAt", x.lastSeenAt)
            .put("touches", x.touches).put("skips", x.skips).put("status", x.status).putOpt("closedAt", x.closedAt)
    }).toString(2))

    // ── 上一次结果 ──
    fun last(): Last? {
        val o = read("last.json") ?: return null
        val s = o.optJSONObject("summary") ?: return null
        return Last(
            o.optLong("endedAt"), o.optString("reason"), o.optStringOrNull("conclusion"),
            Telemetry.Summary(
                s.optInt("generated"), s.optInt("displayed"), s.optInt("replied"), s.optInt("advanced"), s.optInt("resumed"),
                s.optInt("interruptions"), s.optInt("displayedUnproven"), s.optDouble("advanceRate", 0.0),
                s.optInt("awake"), s.optInt("responded"), s.optInt("grounded"),
            ),
            o.optString("topic"), o.optString("task"),
        )
    }

    fun saveLast(l: Last) {
        val s = l.summary
        val sum = JSONObject().put("generated", s.generated).put("displayed", s.displayed).put("replied", s.replied).put("advanced", s.advanced)
            .put("resumed", s.resumed).put("interruptions", s.interruptions).put("displayedUnproven", s.displayedUnproven).put("advanceRate", s.advanceRate)
            .put("awake", s.awake).put("responded", s.responded).put("grounded", s.grounded)
        write("last.json", JSONObject().put("endedAt", l.endedAt).put("reason", l.reason).putOpt("conclusion", l.conclusion)
            .put("summary", sum).put("topic", l.topic).put("task", l.task).toString(2))
    }

    // ── 推进日志 ──
    fun appender(sessionId: String): (Map<String, Any?>) -> Unit {
        val f = File(File(dir, "sessions").apply { mkdirs() }, "$sessionId.jsonl")
        return { row -> f.appendText(JSONObject(row.mapValues { (_, v) -> v ?: JSONObject.NULL }).toString() + "\n") }
    }

    companion object {
        /** Gemini（独立 App，以及 Google App 里的 Live）、ChatGPT。 */
        val DEFAULT_ANCHORS = listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox", "com.openai.chatgpt")
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }

private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
