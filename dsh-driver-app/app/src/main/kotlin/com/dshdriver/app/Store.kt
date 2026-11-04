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
    val observations=ObservationStore(context)
    private val dir = File(context.filesDir, "driver").apply { mkdirs() }

    data class Settings(
        val anchors: List<String> = DEFAULT_ANCHORS,
        val extraAllowed: List<String> = listOf("io.github.ccch1mneyyy.dsh_app"),
        val minutes: Int = 15,
        /** 可选：顺手把总控/一手的无障碍也写回（只追加，不关别人）。默认关。 */
        val peerGuard: Boolean = false,
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
            peerGuard = o.optBoolean("peerGuard", false),
        )
    }

    fun saveSettings(s: Settings) = write("settings.json", JSONObject()
        .put("anchors", JSONArray(s.anchors)).put("extraAllowed", JSONArray(s.extraAllowed)).put("minutes", s.minutes)
        .put("peerGuard", s.peerGuard).toString(2))

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

    // ── 和 DSH Go 的配对（令牌只存在 APP 私有目录） ──
    fun pairing(): DshLink.Pairing? {
        val o = read("pairing.json") ?: return null
        val token = o.optString("token")
        val port = o.optInt("port", 0)
        return if (token.length >= 32 && port in 1..65535) DshLink.Pairing(port, token) else null
    }

    fun savePairing(p: DshLink.Pairing) = write("pairing.json", JSONObject().put("port", p.port).put("token", p.token).toString())

    fun clearPairing() { File(dir, "pairing.json").delete() }

    // ── 拦截娱乐 App（内置，不依赖外部拦截器） ──

    /**
     * [allowedUntil] 是"包名 -> 放行到期"，到期由 [com.dshdriver.core.BlockRules] 判定，读的时候顺手清掉过期的。
     * [dailyMinutes] 是每天所有被拦 App 加起来最多放行多少分钟，0 = 不限；
     * [grantedToday]/[opensToday] 按 [day]（本地日期）在跨天时清零。
     */
    data class Blocking(
        val enabled: Boolean = false,
        val minutes: Int = 10,
        val blocked: List<String> = emptyList(),
        val allowedUntil: Map<String, Long> = emptyMap(),
        val dailyMinutes: Int = 30,
        val minChars: Int = 15,
        val nightBan: Boolean = false,
        val day: String = "",
        val grantedToday: Map<String, Int> = emptyMap(),
        val opensToday: Map<String, Int> = emptyMap(),
    )

    @Synchronized
    fun blocking(): Blocking {
        val o = read("blocking.json") ?: return Blocking()
        val now = System.currentTimeMillis()
        val allowed = o.optJSONObject("allowedUntil")?.let { m ->
            m.keys().asSequence()
                .mapNotNull { k -> m.optLong(k, 0L).takeIf { it > now }?.let { k to it } }
                .toMap()
        }.orEmpty()
        return Blocking(
            enabled = o.optBoolean("enabled", false),
            minutes = o.optInt("minutes", 10).coerceIn(1, 120),
            blocked = o.optJSONArray("blocked").strings(),
            allowedUntil = allowed,
            dailyMinutes = o.optInt("dailyMinutes", 30).coerceIn(0, 600),
            minChars = o.optInt("minChars", 15).coerceIn(15, 200),
            nightBan = o.optBoolean("nightBan", false),
            day = o.optString("day"),
            grantedToday = o.optJSONObject("grantedToday").intMap(),
            opensToday = o.optJSONObject("opensToday").intMap(),
        )
    }

    @Synchronized
    fun saveBlocking(b: Blocking) {
        val allowed = JSONObject().apply { b.allowedUntil.forEach { (pkg, until) -> put(pkg, until) } }
        val granted = JSONObject().apply { b.grantedToday.forEach { (pkg, min) -> put(pkg, min) } }
        val opens = JSONObject().apply { b.opensToday.forEach { (pkg, n) -> put(pkg, n) } }
        write(
            "blocking.json",
            JSONObject()
                .put("enabled", b.enabled)
                .put("minutes", b.minutes)
                .put("blocked", JSONArray(b.blocked))
                .put("allowedUntil", allowed)
                .put("dailyMinutes", b.dailyMinutes)
                .put("minChars", b.minChars)
                .put("nightBan", b.nightBan)
                .put("day", b.day)
                .put("grantedToday", granted)
                .put("opensToday", opens)
                .toString(2),
        )
    }

    /** 最近一次自动重开无障碍的时间（首页显示，让"到底有没有生效"一眼能看到）。 */
    fun lastHeal(): Long = read("heal.json")?.optLong("at", 0L) ?: 0L

    fun saveHeal(at: Long) = write("heal.json", JSONObject().put("at", at).toString())

    /** 拦截门最近问过的题（防止每次都同一句）。 */
    fun recentGateQuestions(): List<String> = readArray("gate_recent.json")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    fun pushGateQuestion(q: String) = write("gate_recent.json", JSONArray((recentGateQuestions() + q).takeLast(5)).toString())

    /**
     * 正在答的那道门。页面被输入法、旋转、低内存或第二次拦截重建时，靠它把题和草稿原样接回来，
     * 而不是重新出一道题。答对或主动放弃时清掉。
     */
    data class GateDraft(
        val target: String,
        val text: String,
        val anchor: String?,
        val missing: String?,
        val agendaId: String?,
        val source: String,
        val draft: String,
        val at: Long,
    )

    /** 关门时刻：晚于它到达的旧草稿写入直接丢掉，避免"答完又被写回来"。 */
    private var gateClosedAt = 0L

    @Synchronized
    fun gateDraft(): GateDraft? {
        val o = read("gate_session.json") ?: return null
        if (o.optLong("at") <= gateClosedAt) return null
        val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
        val target = o.optString("target").takeIf { it.isNotBlank() } ?: return null
        return GateDraft(
            target = target,
            text = text,
            anchor = o.optStringOrNull("anchor"),
            missing = o.optStringOrNull("missing"),
            agendaId = o.optStringOrNull("agendaId"),
            source = o.optString("source", "agenda"),
            draft = o.optString("draft"),
            at = o.optLong("at"),
        )
    }

    @Synchronized
    fun saveGateDraft(d: GateDraft) {
        if (d.at <= gateClosedAt) return
        write(
            "gate_session.json",
            JSONObject()
                .put("target", d.target).put("text", d.text).putOpt("anchor", d.anchor).putOpt("missing", d.missing)
                .putOpt("agendaId", d.agendaId).put("source", d.source).put("draft", d.draft).put("at", d.at)
                .toString(),
        )
    }

    @Synchronized
    fun clearGateDraft() {
        gateClosedAt = System.currentTimeMillis()
        File(dir, "gate_session.json").delete()
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

private fun JSONObject?.intMap(): Map<String, Int> =
    if (this == null) emptyMap()
    else keys().asSequence().mapNotNull { k -> optInt(k, 0).takeIf { it > 0 }?.let { k to it } }.toMap()
