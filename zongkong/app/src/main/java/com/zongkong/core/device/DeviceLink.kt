package com.zongkong.core.device

import com.zongkong.core.AiConfig
import com.zongkong.core.ApiResult
import com.zongkong.core.NotionApi
import com.zongkong.core.NotionClient
import com.zongkong.core.NotionIds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/*
 * 手机总控 ↔ 电脑总控。
 *
 * 两边都已经连着 Notion，就用 Notion 里一个小库“总控·设备”传话：一台设备一行，
 * 每台只写自己那一行（不会互相覆盖），读对方那一行。
 *   手机那行：今天的主线、计划方向、信用和态度、总控时间到几点、发给电脑的命令（锁机、说一句、拦网站）。
 *   电脑那行：今天用了多久、用得最多的、这次会话在做什么、锁没锁、被拦了几次、失联的时段、信用、发给手机的话。
 * 电脑每分钟读一次手机那行、每两分钟写一次自己那行；手机每次同步时读写。
 */

/** 手机发给电脑的命令。kind：lock（锁 [minutes] 分钟，[text] 是原因）、unlock、say（念一句、弹一句）、block（[text] = 网站名，立即拦）。 */
@Serializable
data class DeviceCmd(val id: String, val at: Long, val kind: String, val minutes: Int = 0, val text: String = "")

/** 电脑发给手机的一句话。 */
@Serializable
data class DeviceMsg(val id: String, val at: Long, val text: String)

/** 手机这一行。 */
@Serializable
data class PhoneSide(
    val at: Long = 0,
    val main: String = "",
    val goals: List<String> = emptyList(),
    val aims: List<String> = emptyList(),
    val credibility: Int = 70,
    val stance: String = "",
    /** 总控时间进行到几点（0 = 没在进行）：电脑跟着锁。 */
    val hqUntil: Long = 0,
    val cmds: List<DeviceCmd> = emptyList(),
    /** 已经收到的电脑消息 id。 */
    val gotMsgs: List<String> = emptyList(),
)

@Serializable
data class UsageLine(val label: String, val minutes: Int, val cat: String)

@Serializable
data class GapLine(val from: Long, val to: Long, val why: String = "")

/** 电脑这一行。 */
@Serializable
data class PcSide(
    val at: Long = 0,
    val version: String = "",
    val todayMinutes: Int = 0,
    /** 娱乐 + 游戏 + 社交。 */
    val funMinutes: Int = 0,
    val top: List<UsageLine> = emptyList(),
    val session: String = "",
    val sessionUntil: Long = 0,
    val lockUntil: Long = 0,
    val lockReason: String = "",
    val blocks: Int = 0,
    val gaps: List<GapLine> = emptyList(),
    val credibility: Int = 70,
    val stance: String = "",
    val msgs: List<DeviceMsg> = emptyList(),
    /** 已经执行的手机命令 id。 */
    val doneCmds: List<String> = emptyList(),
)

/** 配对码：手机生成，电脑粘贴。里面有 Notion 密钥和设备库，可选带上 AI 接口（免得电脑上再填一遍）。 */
@Serializable
data class Pairing(val token: String, val devicesDb: String, val baseUrl: String = "", val ai: AiConfig? = null)

object DeviceLink {
    const val DB_TITLE = "总控·设备"
    const val PHONE = "手机"
    const val PC = "电脑"
    const val NAME = "名称"
    const val DATA = "数据"
    private const val PREFIX = "ZKPC1:"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun pairingCode(p: Pairing): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(Pairing.serializer(), p).toByteArray())

    fun parsePairing(code: String): Pairing? = try {
        val t = code.trim().substringAfter(PREFIX, "").filterNot { it.isWhitespace() }
        if (t.isEmpty()) null else json.decodeFromString(Pairing.serializer(), String(Base64.getUrlDecoder().decode(t)))
    } catch (e: Exception) {
        null
    }

    /** 在 [parentPage] 下建设备库（已经有就直接用 [existing]）。 */
    suspend fun ensureDb(api: NotionApi, parentPage: String, existing: String): ApiResult<String> {
        if (existing.isNotBlank()) return ApiResult.Ok(existing)
        val page = NotionIds.parse(parentPage) ?: return ApiResult.Err("Notion 父页面不对：$parentPage")
        val props = buildJsonObject {
            putJsonObject(NAME) { putJsonObject("title") {} }
            putJsonObject(DATA) { putJsonObject("rich_text") {} }
        }
        return api.createDatabase(page, DB_TITLE, props)
    }

    /** 一行：页面 id 和数据（JSON 文本）。 */
    data class Row(val pageId: String, val data: String)

    suspend fun readAll(api: NotionApi, db: String): ApiResult<Map<String, Row>> {
        val out = mutableMapOf<String, Row>()
        var cursor: String? = null
        repeat(3) {
            when (val r = api.query(db, null, cursor)) {
                is ApiResult.Err -> return r
                is ApiResult.Ok -> {
                    r.value.first.forEach { page ->
                        val props = page["properties"] as? JsonObject ?: return@forEach
                        val name = text(props[NAME], "title")
                        val data = text(props[DATA], "rich_text")
                        val id = (page["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        if (name.isNotBlank() && name !in out) out[name] = Row(id, data)
                    }
                    cursor = r.value.second ?: return ApiResult.Ok(out)
                }
            }
        }
        return ApiResult.Ok(out)
    }

    private fun text(prop: kotlinx.serialization.json.JsonElement?, type: String): String {
        val arr = (prop as? JsonObject)?.get(type) as? JsonArray ?: return ""
        return NotionClient.plain(arr)
    }

    /** 写自己那一行：有就改，没有就建。返回页面 id。 */
    suspend fun write(api: NotionApi, db: String, name: String, data: String, pageId: String?): ApiResult<String> {
        val dataProp = buildJsonObject { put("rich_text", NotionClient.richText(data)) }
        if (!pageId.isNullOrBlank()) {
            return when (val r = api.updatePage(pageId, buildJsonObject { put(DATA, dataProp) })) {
                is ApiResult.Ok -> ApiResult.Ok(pageId)
                is ApiResult.Err -> r
            }
        }
        val props = buildJsonObject {
            put(NAME, buildJsonObject { put("title", NotionClient.richText(name)) })
            put(DATA, dataProp)
        }
        return when (val r = api.createPageObject(db, props, JsonArray(emptyList()))) {
            is ApiResult.Ok -> ApiResult.Ok((r.value["id"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            is ApiResult.Err -> r
        }
    }

    fun encodePhone(p: PhoneSide) = json.encodeToString(PhoneSide.serializer(), p)
    fun encodePc(p: PcSide) = json.encodeToString(PcSide.serializer(), p)
    fun decodePhone(s: String): PhoneSide? = runCatching { json.decodeFromString(PhoneSide.serializer(), s) }.getOrNull()
    fun decodePc(s: String): PcSide? = runCatching { json.decodeFromString(PcSide.serializer(), s) }.getOrNull()

    /** 给手机上的总控看的一段（系统提示里、“电脑”页上）。 */
    fun brief(pc: PcSide?, now: Long, zone: java.time.ZoneId): String {
        if (pc == null || pc.at == 0L) return "电脑：还没连上（我 → 电脑 里配对）。"
        fun hm(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        val ago = (now - pc.at) / 60_000
        return buildString {
            append("电脑（${if (ago <= 5) "在线" else "最后一次 $ago 分钟前"}）：今天用了 ${pc.todayMinutes / 60} 小时 ${pc.todayMinutes % 60} 分，娱乐/游戏/社交 ${pc.funMinutes} 分。")
            if (pc.top.isNotEmpty()) append("最多：").append(pc.top.take(4).joinToString("、") { "${it.label} ${it.minutes} 分" }).append("。")
            if (pc.sessionUntil > now) append("这次在做：「${pc.session}」到 ${hm(pc.sessionUntil)}。")
            if (pc.lockUntil > now) append("锁着到 ${hm(pc.lockUntil)}（${pc.lockReason}）。")
            if (pc.blocks > 0) append("今天被拦 ${pc.blocks} 次。")
            if (pc.gaps.isNotEmpty()) append("电脑上的总控被关掉过：").append(pc.gaps.takeLast(3).joinToString("、") { "${hm(it.from)}–${hm(it.to)}" }).append("。")
            append("电脑那边的信用 ${pc.credibility}（${pc.stance}）。")
        }
    }
}
