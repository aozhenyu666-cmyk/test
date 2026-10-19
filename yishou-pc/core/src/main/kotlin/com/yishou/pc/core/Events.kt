package com.yishou.pc.core

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class EventType {
    /** 守门页弹出来了 */
    GATE,
    /** 说清去做什么、回来做什么，放行几分钟 */
    GRANT,
    /** 不去了 */
    DECLINE,
    /** 真有急事：写了理由，放行 5 分钟 */
    EMERGENCY,
    /** 时间到，回去做 */
    RETURN,
    /** 回答“上次说回来做的，做了吗” */
    FOLLOWUP,
    /** 从托盘退出（写了原因） */
    QUIT,
    /** 上次没有正常退出（任务管理器结束、断电等） */
    UNCLEAN,
    FOCUS_START,
    FOCUS_STOP,
}

/** 逃跑记录里的一条。 */
data class Event(
    val time: Long,
    val type: EventType,
    val category: String = "",
    /** 想打开的东西：程序名或窗口标题 */
    val target: String = "",
    val minutes: Int = 0,
    /** 去做什么 */
    val what: String = "",
    /** 回来做什么 */
    val then: String = "",
    /** FOLLOWUP：做了没有 */
    val done: Boolean = false,
    /** 理由、备注 */
    val note: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("t", time).put("type", type.name).put("c", category).put("target", target)
        .put("min", minutes).put("what", what).put("then", then).put("done", done).put("note", note)
        .toString()

    companion object {
        fun fromJson(line: String): Event? = try {
            val o = JSONObject(line)
            Event(
                time = o.getLong("t"),
                type = EventType.valueOf(o.getString("type")),
                category = o.optString("c"),
                target = o.optString("target"),
                minutes = o.optInt("min"),
                what = o.optString("what"),
                then = o.optString("then"),
                done = o.optBoolean("done"),
                note = o.optString("note"),
            )
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** 逃跑记录按天存，每天一个文件，每行一条 JSON；只留最近 [keepDays] 天。 */
class EventStore(private val dir: File, private val zone: () -> ZoneId = ZoneId::systemDefault, private val keepDays: Long = 90) {

    private fun file(date: LocalDate) = File(dir, "events-$date.jsonl")

    @Synchronized
    fun append(e: Event) {
        dir.mkdirs()
        val date = Instant.ofEpochMilli(e.time).atZone(zone()).toLocalDate()
        file(date).appendText(e.toJson() + "\n")
        prune(date)
    }

    @Synchronized
    fun read(date: LocalDate): List<Event> {
        val f = file(date)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { if (it.isBlank()) null else Event.fromJson(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun prune(today: LocalDate) {
        val cutoff = "events-${today.minusDays(keepDays)}.jsonl"
        dir.listFiles()?.filter { it.name.startsWith("events-") && it.name < cutoff }?.forEach { it.delete() }
    }
}
