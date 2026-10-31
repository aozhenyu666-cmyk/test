package com.yishou.app.assistant

import org.json.JSONArray
import org.json.JSONObject

data class ChatLine(val role: String, val text: String)

object ChatHistory {
    const val MAX_CONTEXT = 24_000
    fun encode(lines: List<ChatLine>): String = JSONArray(lines.map { JSONObject().put("role", it.role).put("text", it.text) }).toString()
    fun decode(json: String): List<ChatLine> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it).let { ChatLine(it.getString("role"), it.getString("text")) } }
    }
    fun context(lines: List<ChatLine>): List<Pair<String, String>> {
        val recent = lines.filter { it.role == "user" || it.role == "assistant" }.takeLast(16).toMutableList()
        while (recent.isNotEmpty() && (recent.first().role != "user" || recent.sumOf { it.text.length } > MAX_CONTEXT)) recent.removeAt(0)
        return recent.map { it.role to it.text }
    }
    val system = "你是一手内置的对话助手。用中文直接回答用户的问题，可以讨论、解释和一起想事。记住这段对话里已确认的事实，不迎合没有证据的结论。需要用户补信息时一次只问一个具体问题。没有查证能力就说明，不编造来源。你不能操作手机、改设置或签发娱乐放行。除非用户要求，别把普通聊天变成训练或打分。"
}
