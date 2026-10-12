package com.zongkong.core.agent

import com.zongkong.core.AiConfig
import com.zongkong.core.ApiResult
import com.zongkong.core.LlmClient
import com.zongkong.core.Net
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** 参谋对话里的一条。工具调用和结果也存下来，界面折叠显示。 */
@Serializable
data class ChatMsg(
    val role: String,
    val content: String = "",
    val at: Long = 0,
    /** assistant 发起的工具调用。 */
    val calls: List<ToolCall> = emptyList(),
    /** role = tool 时：回应的是哪个调用。 */
    val callId: String = "",
    /** role = tool 时：给你看的一句话（“新建了行动「…」”）。 */
    val summary: String = "",
    /** role = tool 时：执行成功没有。 */
    val ok: Boolean = true,
    /** role = tool 时：参谋要打开的页面。 */
    val route: String = "",
    /** 这条是出错提示（不发给模型）。 */
    val error: Boolean = false,
    /** 定时任务替你说的话（发给模型，但界面上不显示成你的话）。 */
    val hidden: Boolean = false,
    /** 附带的图片（本机文件路径），发给识图模型。 */
    val image: String = "",
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
    }
}

@Serializable
data class ToolCall(val id: String, val name: String, val args: String)

/** 模型这一轮的回复：要么一段话，要么一组工具调用（也可能两者都有）。 */
data class Turn(val content: String, val calls: List<ToolCall>)

/** 能调用工具的对话接口。测试时换成假的。 */
fun interface ChatApi {
    suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): ApiResult<Turn>
}

/** OpenAI 兼容的 Chat Completions + tools（DeepSeek、Kimi、通义、智谱、OpenAI 都支持）。 */
class ChatClient(
    private val config: () -> AiConfig,
    private val http: OkHttpClient = Net.client(90),
) : ChatApi {

    override suspend fun chat(system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): ApiResult<Turn> {
        val cfg = config()
        if (!cfg.ready) return ApiResult.Err("还没有配置 AI 接口（我 → AI 接口）")
        val url = LlmClient.chatUrl(cfg.baseUrl) ?: return ApiResult.Err("接口地址格式不对：${cfg.baseUrl}")
        val hasImage = messages.lastOrNull { it.role == ChatMsg.USER }?.image?.isNotBlank() == true
        val model = if (hasImage && cfg.visionModel.isNotBlank()) cfg.visionModel.trim() else cfg.model.trim()
        val body = request(model, system, messages, tools)
        val raw = when (val r = Net.send(http, Net.post(url, body, mapOf("Authorization" to "Bearer ${cfg.apiKey.trim()}")))) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        if (raw.code !in 200..299) {
            val detail = Net.errorMessage(raw.body)
            val hint = if (raw.code == 400 && detail.contains("tool", ignoreCase = true)) "（这个模型可能不支持工具调用，换一个模型试试，比如 deepseek-chat）" else ""
            return ApiResult.Err(LlmClient.httpMessage(raw.code, detail) + hint)
        }
        return parse(raw.body)
    }

    companion object {
        fun request(model: String, system: String, messages: List<ChatMsg>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("temperature", 0.3)
            // 只有最后一条带图的消息真的发图，之前的图只留一句“[图片]”，省流量
            val lastImage = messages.indexOfLast { it.role == ChatMsg.USER && it.image.isNotBlank() }
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                messages.forEachIndexed { i, m -> add(wire(m, sendImage = i == lastImage)) }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray { tools.forEach { add(it.wire()) } })
                put("tool_choice", "auto")
            }
        }

        /** 图片转成 data URL（JPEG，读不到返回 null）。 */
        fun imageUrl(path: String): String? = try {
            val f = java.io.File(path)
            if (!f.exists() || f.length() > 4_000_000) null
            else "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(f.readBytes())
        } catch (e: Exception) {
            null
        }

        private fun wire(m: ChatMsg, sendImage: Boolean = false): JsonObject = buildJsonObject {
            put("role", m.role)
            when (m.role) {
                ChatMsg.TOOL -> {
                    put("tool_call_id", m.callId)
                    put("content", m.content)
                }
                ChatMsg.ASSISTANT -> {
                    put("content", m.content)
                    if (m.calls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            m.calls.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    put("function", buildJsonObject { put("name", c.name); put("arguments", c.args) })
                                })
                            }
                        })
                    }
                }
                else -> {
                    val url = if (sendImage && m.image.isNotBlank()) imageUrl(m.image) else null
                    if (url != null) {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", m.content.ifBlank { "看看这张图" }) })
                            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) })
                        })
                    } else {
                        put("content", if (m.image.isNotBlank()) "${m.content}\n[图片]".trim() else m.content)
                    }
                }
            }
        }

        fun parse(body: String): ApiResult<Turn> {
            val msg = try {
                Net.parseObject(body)?.get("choices")?.jsonArray?.get(0)?.jsonObject?.get("message")?.jsonObject
            } catch (e: Exception) {
                null
            } ?: return ApiResult.Err("AI 返回的结构不对：${body.take(120)}")
            val content = (msg["content"]?.takeIf { it !is JsonNull })?.jsonPrimitive?.contentOrNull.orEmpty()
            val calls = (msg["tool_calls"]?.takeIf { it !is JsonNull } as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
                val o = el as? JsonObject ?: return@mapIndexedNotNull null
                val fn = o["function"] as? JsonObject ?: return@mapIndexedNotNull null
                val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                val args = fn["arguments"]?.let { a -> if (a is JsonObject) a.toString() else a.jsonPrimitive.contentOrNull } ?: "{}"
                ToolCall(o["id"]?.jsonPrimitive?.contentOrNull ?: "call_$i", name, args.ifBlank { "{}" })
            }
            if (content.isBlank() && calls.isEmpty()) return ApiResult.Err("AI 返回了空回复")
            return ApiResult.Ok(Turn(content, calls))
        }
    }
}
