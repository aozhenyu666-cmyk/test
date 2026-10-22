package com.moling.app.mirror

import android.util.Log
import com.moling.app.data.SettingsStore
import com.moling.core.mind.BEIJING
import com.moling.core.mirror.Absorbed
import com.moling.core.mirror.InboxItem
import com.moling.core.mirror.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 和联络站同步（v10）：ChatGPT / Claude 通过 MCP 连到联络站，墨灵每 10 分钟来推黑板、收回答。
 * 联络站的代码在 assets/relay-worker.js（贯通页可以一键复制去部署）。
 * 收来的和 Notion 收件箱一样交给大脑把关：回答挂到问题上、论断进体系图、改“了解”要他点好。
 */
object RelaySync {
    private const val TAG = "RelaySync"
    private const val EVERY_MS = 10 * 60_000L
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build() }

    @Volatile private var lastAt = 0L
    @Volatile private var pushedHash = 0

    /** [pushed] = 这次真的把黑板推上去了（和收件箱有没有收成功分开：推送成功但收件箱失败时仍算推上去了）。 */
    data class Result(val status: String, val absorbed: List<Absorbed>, val pushed: Boolean = false)

    suspend fun sync(
        store: SettingsStore,
        now: Long,
        force: Boolean,
        board: () -> Pair<String, List<Question>>,
        seen: () -> Collection<String>,
        /** 第十二批：外脑会话的结果先在这里认领（带 [[外脑:ob-…]] 标记的），认领过的不再交给大脑重复收。返回认领的 InboxItem id。 */
        routeOutbrain: suspend (List<InboxItem>) -> Set<String> = { emptySet() },
        absorb: suspend (List<InboxItem>) -> Absorbed,
    ): Result? {
        val r = store.relay()
        if (!r.ready()) return null
        if (!force && now - lastAt < EVERY_MS) return null
        lastAt = now
        // 推送结果要能单独报出去（外脑会话“推到联络站”只说推上去了，不说外脑已读）。
        var pushed = false
        return try {
            val (text, qs) = board()
            if (force || text.hashCode() != pushedHash) {
                val body = buildJsonObject {
                    put("text", text)
                    putJsonArray("questions") { qs.filter { it.answer.isBlank() }.forEach { q -> addJsonObject { put("id", q.id); put("text", q.text) } } }
                }
                call(r.base() + "/api/board", r.token, "PUT", body.toString())
                pushedHash = text.hashCode()
                pushed = true
            }
            val after = store.relayAfter()
            val got = json.parseToJsonElement(call(r.base() + "/api/inbox?after=$after", r.token, "GET", null)).jsonObject
            val items = got["items"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val inbox = items.map { o ->
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val q = s("q")
                InboxItem("relay:${s("id")}", (if (q.isNotBlank()) "$q：" else "") + s("text"), s("from").ifBlank { "AI" } + "（联络站）")
            }
            val results = mutableListOf<Absorbed>()
            val routed = runCatching { routeOutbrain(inbox) }.getOrDefault(emptySet())
            val forBrain = inbox.filterNot { it.id in routed }
            for (part in forBrain.chunked(4)) results += absorb(part)
            // 大脑把关过（记成“收过”）的才往前挪；外脑认领的也算收过；模型没接上时不挪，下次再收
            val known = seen().toSet()
            val ok = inbox.all { it.id in known || it.id in routed }
            val last = (got["last"] as? JsonPrimitive)?.longOrNull ?: after
            if (inbox.isEmpty() || ok) store.setRelayAfter(last)
            val t = Instant.ofEpochMilli(now).atZone(BEIJING)
            val status = "%02d:%02d ".format(t.hour, t.minute) + buildString {
                append(if (pushed) "黑板推上去了" else "黑板没变")
                append(if (inbox.isEmpty()) "，没有新回答" else "，收了 ${inbox.size} 条" + if (routed.isNotEmpty()) "（外脑 ${routed.size} 条）" else "")
                results.flatMap { it.answered }.takeIf { it.isNotEmpty() }?.let { append("，回答了 ${it.joinToString("、")}") }
                if (inbox.isNotEmpty() && !ok) append("（模型没接上，下次再收）")
            }
            store.setRelayStatus(status)
            Result(status, results, pushed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "同步失败", e)
            val status = "同步失败：${e.message ?: e.javaClass.simpleName}"
            store.setRelayStatus(status)
            // 黑板推上去了、后面收件箱才失败：推送这件事仍然算数，不能报成“没推上去”。
            Result(status, emptyList(), pushed)
        }
    }

    private suspend fun call(url: String, token: String, method: String, body: String?): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        val req = when (method) {
            "GET" -> b.get()
            else -> b.method(method, (body ?: "{}").toRequestBody(JSON_TYPE))
        }.build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when (resp.code) {
                in 200..299 -> text
                401 -> error("令牌不对（联络站的 MOLING_TOKEN 和这里填的要一样）")
                404 -> error("地址不对，或者联络站代码没部署上")
                else -> error("联络站返回 ${resp.code}")
            }
        }
    }

    /** 随机一串（生成令牌 / MCP 地址里的那段用）。 */
    fun secret(): String {
        val r = java.security.SecureRandom()
        return (1..32).joinToString("") { "0123456789abcdef"[r.nextInt(16)].toString() }
    }
}
