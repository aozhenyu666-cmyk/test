package com.dshdriver.app

import com.dshdriver.core.Agenda
import com.dshdriver.core.Brain
import com.dshdriver.core.ComposeRequest
import com.dshdriver.core.Draft
import com.dshdriver.core.Options
import com.dshdriver.core.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 和 DSH Go 里的 dsh-driver-core 插件说话（只连本机 127.0.0.1）。
 *   pair     配对码换令牌
 *   ping     令牌还有效吗、出题会话认领了没、记忆几条
 *   compose  出一题（大肥鱼的模型 + fish-memory）
 *   report   锁定结束报告
 * 全部是阻塞调用：只在后台线程用（驾驶员线程或首页开的线程）。
 */
object DshLink {
    data class Pairing(val port: Int, val token: String)
    data class Ping(val ok: Boolean, val brainConfigured: Boolean, val memories: Int, val error: String?)

    private const val PREFIX = "/dsh-driver-core/v1/app"

    /** "3080:K7PQ-M3XA" 或只有 "K7PQ-M3XA"（默认端口 3080）。 */
    fun parseCode(input: String): Pair<Int, String>? {
        val parts = input.trim().replace('：', ':').split(':')
        val port: Int
        val code: String
        when (parts.size) {
            1 -> { port = 3080; code = parts[0] }
            2 -> { port = parts[0].trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null; code = parts[1] }
            else -> return null
        }
        return if (code.count(Char::isLetterOrDigit) == 8) port to code else null
    }

    /** 成功返回配对信息；失败返回给人看的原因。 */
    fun pair(input: String, device: String): Result<Pairing> {
        val (port, code) = parseCode(input) ?: return Result.failure(IllegalArgumentException("配对码格式不对，应该像 3080:K7PQ-M3XA"))
        val (status, body) = call(port, null, "POST", "/pair", JSONObject().put("code", code).put("device", device))
            ?: return Result.failure(IllegalStateException("连不上 127.0.0.1:$port：DSH Go 开着吗？插件装好了吗？"))
        val token = body?.optString("token").orEmpty()
        return when {
            status == 200 && token.length >= 32 -> Result.success(Pairing(port, token))
            status == 429 -> Result.failure(IllegalStateException("试错太多次，等 1 分钟再试"))
            body?.optJSONObject("error")?.optString("code") == "code-expired" -> Result.failure(IllegalStateException("配对码过期了，让大肥鱼再生成一个"))
            status == 403 -> Result.failure(IllegalStateException("配对码不对，或者已经用过了"))
            status == 404 -> Result.failure(IllegalStateException("DSH Go 里没有 dsh-driver-core 插件"))
            else -> Result.failure(IllegalStateException("配对失败（HTTP $status）"))
        }
    }

    fun ping(p: Pairing): Ping {
        val (status, body) = call(p.port, p.token, "GET", "/ping", null) ?: return Ping(false, false, 0, "连不上 DSH Go（没开，或引擎没在跑）")
        if (status == 401) return Ping(false, false, 0, "配对已失效，需要重新配对")
        if (status != 200 || body == null) return Ping(false, false, 0, "插件没有正常应答（HTTP $status）")
        return Ping(true, body.optString("brain") == "configured", body.optInt("memories"), null)
    }

    /** 驾驶员的大脑：拿不到就返回 null，驾驶员用模板。 */
    fun brain(p: Pairing): Brain = Brain { r ->
        val (status, body) = call(p.port, p.token, "POST", "/compose", requestJson(r), readTimeoutMs = 9_000) ?: return@Brain null
        if (status != 200) return@Brain null
        body?.optJSONObject("card")?.let(::draftOf)
    }

    fun report(p: Pairing, last: Store.Last, agenda: Agenda) {
        val s: Telemetry.Summary = last.summary
        val sum = JSONObject().put("grounded", s.grounded).put("responded", s.responded).put("awake", s.awake)
            .put("interruptions", s.interruptions).put("resumed", s.resumed).put("generated", s.generated)
        val body = JSONObject().put("endedAt", last.endedAt).put("topic", last.topic).put("task", last.task).put("reason", last.reason)
            .putOpt("conclusion", last.conclusion).put("summary", sum).put("unresolved", JSONArray(agenda.open().take(8).map { it.text }))
        call(p.port, p.token, "POST", "/report", body)
    }

    private fun requestJson(r: ComposeRequest) = JSONObject()
        .put("intent", r.intent).put("cog", JSONObject().put("state", r.cog.state.name).put("reason", r.cog.reason)).putOpt("die", r.die)
        .put("topic", r.topic).put("task", r.task).put("minute", r.minute).put("minutes", r.minutes)
        .putOpt("breakpoint", r.breakpoint).putOpt("lastQuestion", r.lastQuestion).putOpt("missing", r.missing)
        .put("recentReplies", JSONArray(r.recentReplies)).put("asked", JSONArray(r.asked))
        .put("goals", JSONArray(r.goals)).put("habits", JSONArray(r.habits)).put("patterns", JSONArray(r.patterns)).put("unresolved", JSONArray(r.unresolved))

    private fun draftOf(c: JSONObject): Draft? {
        val kind = c.optString("kind").takeIf { it.isNotEmpty() } ?: return null
        val text = c.optString("text").takeIf { it.isNotBlank() } ?: return null
        val o = c.optJSONObject("options")
        val options = if (o != null && o.optString("A").isNotBlank() && o.optString("B").isNotBlank()) Options(o.optString("A"), o.optString("B")) else null
        // expects 和最终校验由 core 的 Drafts.validate 统一做
        return Draft(kind, text, "evidence", options, anchor = c.optString("anchor").takeIf { it.isNotBlank() }, missing = c.optString("missing").takeIf { it.isNotBlank() }, source = "llm")
    }

    /** 一次 HTTP 调用。连不上返回 null；其余返回 (状态码, JSON 或 null)。 */
    private fun call(port: Int, token: String?, method: String, path: String, body: JSONObject?, readTimeoutMs: Int = 4_000): Pair<Int, JSONObject?>? = runCatching {
        val conn = URL("http://127.0.0.1:$port$PREFIX$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 1_500
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            status to runCatching { JSONObject(text) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
