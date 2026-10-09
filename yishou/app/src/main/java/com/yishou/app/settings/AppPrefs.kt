package com.yishou.app.settings

import org.json.JSONArray
import org.json.JSONObject

/** 一个应用组：同组应用共用放行。 */
data class AppGroup(
    val name: String,
    /** 每次放行时长，5–30 分钟 */
    val passMinutes: Int,
)

/**
 * 除大模型接口以外的全部设置。整体存成一段 JSON，缺少的项用默认值，
 * 以后加新设置项时旧数据也能读。
 */
data class AppPrefs(
    val groups: List<AppGroup> = DEFAULT_GROUPS,
    /** 包名 → 应用组名 */
    val watched: Map<String, String> = DEFAULT_WATCHED,
    val offlinePassMinutes: Int = 5,
    val offlineDailyLimit: Int = 3,
    val windowEnabled: Boolean = false,
    val windowHour: Int = 20,
    val windowMinute: Int = 0,
    val windowMinutes: Int = 45,
    val remindFirstMinutes: Int = 3,
    val remindSecondMinutes: Int = 1,
    val ttsEnabled: Boolean = true,
    val summaryEnabled: Boolean = true,
    val summaryHour: Int = 22,
    val summaryMinute: Int = 30,
    /** 手动开始的陪练窗口的开始时间，0 表示没有 */
    val manualWindowStart: Long = 0,
    /** 最近一次“提前结束窗口”的时间，0 表示没有 */
    val windowStoppedAt: Long = 0,
    /** 权限页里手动勾选“已设置好”的厂商项 */
    val manualChecks: Set<String> = emptySet(),
) {
    fun groupOf(pkg: String): String? = watched[pkg]

    fun passMinutesFor(group: String): Int =
        groups.firstOrNull { it.name == group }?.passMinutes ?: DEFAULT_PASS_MINUTES

    fun toJson(): String = JSONObject()
        .put("groups", JSONArray().apply { groups.forEach { put(JSONObject().put("name", it.name).put("minutes", it.passMinutes)) } })
        .put("watched", JSONObject().apply { watched.forEach { (k, v) -> put(k, v) } })
        .put("offlinePassMinutes", offlinePassMinutes)
        .put("offlineDailyLimit", offlineDailyLimit)
        .put("windowEnabled", windowEnabled)
        .put("windowHour", windowHour)
        .put("windowMinute", windowMinute)
        .put("windowMinutes", windowMinutes)
        .put("remindFirstMinutes", remindFirstMinutes)
        .put("remindSecondMinutes", remindSecondMinutes)
        .put("ttsEnabled", ttsEnabled)
        .put("summaryEnabled", summaryEnabled)
        .put("summaryHour", summaryHour)
        .put("summaryMinute", summaryMinute)
        .put("manualWindowStart", manualWindowStart)
        .put("windowStoppedAt", windowStoppedAt)
        .put("manualChecks", JSONArray().apply { manualChecks.forEach { put(it) } })
        .toString()

    companion object {
        const val DEFAULT_PASS_MINUTES = 10
        const val MIN_PASS_MINUTES = 5
        const val MAX_PASS_MINUTES = 30

        val DEFAULT_GROUPS = listOf(AppGroup("短视频与社区", 10), AppGroup("游戏", 10))

        /** 默认关注：抖音、B站、贴吧、三角洲行动。没装的应用不影响。 */
        val DEFAULT_WATCHED = mapOf(
            "com.ss.android.ugc.aweme" to "短视频与社区",
            "tv.danmaku.bili" to "短视频与社区",
            "com.baidu.tieba" to "短视频与社区",
            "com.tencent.tmgp.dfm" to "游戏",
        )

        /** 读不出来（空、损坏）时返回默认设置，不让应用崩溃。 */
        fun fromJson(text: String?): AppPrefs {
            if (text.isNullOrBlank()) return AppPrefs()
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                return AppPrefs()
            }
            val d = AppPrefs()
            val groups = o.optJSONArray("groups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { g ->
                        val name = g.optString("name").trim()
                        if (name.isEmpty()) null
                        else AppGroup(name, g.optInt("minutes", DEFAULT_PASS_MINUTES).coerceIn(MIN_PASS_MINUTES, MAX_PASS_MINUTES))
                    }
                }.distinctBy { it.name }
            }?.takeIf { it.isNotEmpty() } ?: d.groups
            val groupNames = groups.map { it.name }.toSet()
            val watched = o.optJSONObject("watched")?.let { w ->
                w.keys().asSequence().associateWith { w.optString(it) }
                    // 组被删掉的应用归到第一组，不会因此失去关注
                    .mapValues { (_, g) -> if (g in groupNames) g else groups.first().name }
            } ?: d.watched
            val checks = o.optJSONArray("manualChecks")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
            } ?: d.manualChecks
            return AppPrefs(
                groups = groups,
                watched = watched,
                offlinePassMinutes = o.optInt("offlinePassMinutes", d.offlinePassMinutes).coerceIn(1, 30),
                offlineDailyLimit = o.optInt("offlineDailyLimit", d.offlineDailyLimit).coerceIn(0, 10),
                windowEnabled = o.optBoolean("windowEnabled", d.windowEnabled),
                windowHour = o.optInt("windowHour", d.windowHour).coerceIn(0, 23),
                windowMinute = o.optInt("windowMinute", d.windowMinute).coerceIn(0, 59),
                windowMinutes = o.optInt("windowMinutes", d.windowMinutes).coerceIn(5, 180),
                remindFirstMinutes = o.optInt("remindFirstMinutes", d.remindFirstMinutes).coerceIn(1, 30),
                remindSecondMinutes = o.optInt("remindSecondMinutes", d.remindSecondMinutes).coerceIn(1, 30),
                ttsEnabled = o.optBoolean("ttsEnabled", d.ttsEnabled),
                summaryEnabled = o.optBoolean("summaryEnabled", d.summaryEnabled),
                summaryHour = o.optInt("summaryHour", d.summaryHour).coerceIn(0, 23),
                summaryMinute = o.optInt("summaryMinute", d.summaryMinute).coerceIn(0, 59),
                manualWindowStart = o.optLong("manualWindowStart", 0),
                windowStoppedAt = o.optLong("windowStoppedAt", 0),
                manualChecks = checks,
            )
        }
    }
}
