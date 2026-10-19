package com.yishou.app.llm

import org.json.JSONException
import org.json.JSONObject

/**
 * OpenAI 兼容接口的流式返回（Server-Sent Events）：一行一行的 `data: {...}`，最后 `data: [DONE]`。
 * 网络给的块可能在一行中间断开，所以先攒到换行再解析。纯逻辑，便于测试。
 */
class SseParser {
    private val pending = StringBuilder()

    /** 读到 [DONE] */
    var done = false
        private set

    /** 流里夹带的错误（有的服务商出错时也用 data: {"error": …} 返回） */
    var error: String? = null
        private set

    /** 喂一块网络数据，返回这块里新出现的文字片段（按顺序）。 */
    fun feed(chunk: String): List<String> {
        if (done) return emptyList()
        pending.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0) break
            val line = pending.substring(0, nl).trimEnd('\r')
            pending.delete(0, nl + 1)
            line(line)?.let { out += it }
            if (done) break
        }
        return out
    }

    /** 流断了但最后一行没换行：把剩下的也解析掉 */
    fun finish(): List<String> {
        if (done || pending.isEmpty()) return emptyList()
        val last = pending.toString().trimEnd('\r')
        pending.clear()
        return listOfNotNull(line(last))
    }

    private fun line(line: String): String? {
        if (!line.startsWith("data:")) return null // 空行、注释（:）、event: 都跳过
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") {
            done = true
            return null
        }
        return try {
            val o = JSONObject(data)
            o.optJSONObject("error")?.let { e ->
                error = e.optString("message").ifBlank { e.toString() }.take(200)
                return null
            }
            val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            delta?.optString("content", "")?.takeIf { it.isNotEmpty() && it != "null" }
        } catch (e: JSONException) {
            null
        }
    }
}
