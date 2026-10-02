package huilu.app

import android.widget.Toast
import huilu.core.CheckIn
import huilu.core.LlmJudge
import huilu.core.Via
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 调用 OpenAI 兼容接口的判断层。失败不影响闭环：检查保持打开，用户点按钮即可。 */
object Judge {
    private val exec = Executors.newSingleThreadExecutor()

    /** 最近一次判断层对某个检查的回应（追问或说明），检查页会显示它。 */
    @Volatile var lastReply: Pair<String, String>? = null

    fun interpret(app: App, c: CheckIn, text: String, done: () -> Unit = {}) {
        val a = app.engine.situation.action
        if (a == null) { done(); return }
        val msgs = LlmJudge.messages(c, a, text, app.episodes().filter { it.outcome != null }, app.platform::label)
        val p = app.prefs
        val base = p.llmBase; val model = p.llmModel; val key = p.llmKey
        exec.execute {
            val r = runCatching { complete(base, model, key, msgs) }
            app.main.post {
                try { handle(app, c, text, r) } finally { done() }
            }
        }
    }

    private fun handle(app: App, c: CheckIn, text: String, r: Result<String>) {
        val still = app.engine.situation.pending?.id == c.id
        val raw = r.getOrElse { e ->
            app.perform(app.engine.note(c.id, "判断层调用失败：${e.message ?: e.javaClass.simpleName}", Via.LLM))
            if (still) app.notifier.showCheckIn(c, fullScreen = false, extra = "AI 判断暂时不可用。请点一个选项。")
            return
        }
        val it = LlmJudge.parse(raw, c.choices)
        if (it == null) {
            app.perform(app.engine.note(c.id, "判断层输出无法解析", Via.LLM))
            if (still) app.notifier.showCheckIn(c, fullScreen = false, extra = "没能理解，请点一个选项。")
            return
        }
        if (it.assessment.isNotBlank()) app.perform(app.engine.note(c.id, "判断层：${it.assessment}", Via.LLM))
        if (!still) return
        val kind = it.kind
        if (kind != null) {
            app.perform(app.engine.answer(c.id, kind, text, Via.LLM, it.minutes, it.newText))
            val decision = app.engine.situation.lastClosed?.decision.orEmpty()
            Toast.makeText(app, listOf(it.reply, decision).filter { s -> s.isNotBlank() }.joinToString("\n"), Toast.LENGTH_LONG).show()
        } else {
            lastReply = c.id to it.reply
            val pending = app.engine.situation.pending ?: return
            app.notifier.showCheckIn(pending, fullScreen = false, extra = it.reply.ifBlank { "能再具体一点吗？" })
            app.perform(emptyList())
        }
    }

    fun test(base: String, model: String, key: String, done: (String) -> Unit) {
        exec.execute {
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "只回复两个字：可以"))
            val r = runCatching { complete(base, model, key, msgs) }
            App.instance.main.post { done(r.fold({ "连接成功：${it.take(40)}" }, { "失败：${it.message ?: it.javaClass.simpleName}" })) }
        }
    }

    private fun complete(base: String, model: String, key: String, msgs: JSONArray): String {
        val url = URL(base.trimEnd('/') + "/chat/completions")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.2)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(200)}")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {
            c.disconnect()
        }
    }
}
