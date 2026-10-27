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
    val recallDay: Long = Long.MIN_VALUE,
    val recallIds: Set<Long> = emptySet(),
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
    /** 每天检查一次：当天还没应一手就提醒 */
    val nudgeEnabled: Boolean = true,
    val nudgeHour: Int = 21,
    val nudgeMinute: Int = 0,
    /** 开局规则：窗口里只允许下面这些应用（休息中不限制） */
    val windowStrict: Boolean = true,
    /** 窗口里随便用的学习应用 */
    val windowAllowed: Set<String> = emptySet(),
    /** 窗口里可以短暂切过去的应用（每次最多 windowShortMinutes 分钟） */
    val windowShortApps: Set<String> = DEFAULT_SHORT_APPS,
    val windowShortMinutes: Int = 2,
    /** “先停”休息到什么时候；休息中不检查开局规则 */
    val windowPauseUntil: Long = 0,
    /** 窗口里自动“看一眼”的间隔分钟，0 表示只在点“看一眼”时看 */
    val lookIntervalMinutes: Int = 0,
    /** 分组训练：一组几手有效回答 */
    val setSize: Int = 3,
    /** 组间休息几分钟 */
    val restMinutes: Int = 2,
    /** 起步每天几组（之后按达标情况自动加减） */
    val baseSets: Int = 2,
    /** 界面：0 跟随系统，1 白局（浅色），2 夜局（深色） */
    val themeMode: Int = 0,
    /** 摇一摇手机掷骰子 */
    val shakeToRoll: Boolean = true,
    /** 手动开始的陪练窗口的开始时间，0 表示没有 */
    val manualWindowStart: Long = 0,
    /** 最近一次“提前结束窗口”的时间，0 表示没有 */
    val windowStoppedAt: Long = 0,
    /** 权限页里手动勾选“已设置好”的厂商项 */
    val manualChecks: Set<String> = emptySet(),
    /** 窗口里第二次提醒后还没动静，把窗口页拉回前台 */
    val pullBackEnabled: Boolean = true,
    /** 第二次提醒后每隔几分钟拉回一次 */
    val pullBackMinutes: Int = 2,
    /** 白天一手停了这么多小时没人应，发通知叫回来；0 表示关 */
    val idleHours: Int = 3,
    /** 语音陪练：窗口里读完题自动听你说 */
    val voiceMode: Boolean = true,
    /** 听写完几秒后自动发出，0 表示不自动发、等你确认 */
    val voiceAutoSendSeconds: Int = 3,
    /** 时区：默认固定北京时间，"system" 表示跟随手机 */
    val timeZone: String = AppZone.BEIJING,
    /** 震动提示：新的一手、提醒、拉回、判定结果都有各自的震法 */
    val haptics: Boolean = true,
    /** 一组训练几手（不含重做） */
    val coachSetMoves: Int = 5,
    /** 一组几张题卡 */
    val setCards: Int = 5,
    /** 入口思考页要不要做一张题卡；关掉就只是打卡：说好看完做什么就放行 */
    val gateCard: Boolean = true,
    /** 入口思考页做什么：endgame 残局、card 一张题卡、line 主线下一手、check 只打卡 */
    val gateMode: String = "card",
    /** 强度：LIGHT 轻 / MID 中 / HARD 强（口水规则、回主线的频率、做事要不要写判断） */
    val talkLevel: String = "MID",
    /** 题卡先用自己的话说答案，再看选项 */
    val predictFirst: Boolean = true,
    /** 出题用的 AI App，每行“名字 包名 网址” */
    val bankLinks: String = com.yishou.app.outside.AiApps.DEFAULT,
    /** 刷题 App（粉笔……），每行“名字 包名 网址” */
    val drillApps: String = com.yishou.app.outside.AiApps.DEFAULT_DRILL,
    /** 入口思考页放行时说的“看完做的第一件事”，下次打开先问做了没有 */
    val pendingExit: String = "",
    /** 那次放行到期的时间；过了这个时间才问 */
    val pendingExitAt: Long = 0,
    /** 开组前先热身 */
    val warmup: Boolean = true,
    /** 各骰面的帮助等级（0 示范后补一步 … 3 连续接力、自己提下一问），按组的表现自动加减 */
    val faceLevels: Map<Int, Int> = emptyMap(),
) {
    fun groupOf(pkg: String): String? = watched[pkg]

    fun passMinutesFor(group: String): Int =
        groups.firstOrNull { it.name == group }?.passMinutes ?: DEFAULT_PASS_MINUTES

    fun toJson(): String = JSONObject()
        .put("recallDay", recallDay)
        .put("recallIds", JSONArray(recallIds.toList()))
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
        .put("nudgeEnabled", nudgeEnabled)
        .put("nudgeHour", nudgeHour)
        .put("nudgeMinute", nudgeMinute)
        .put("windowStrict", windowStrict)
        .put("windowAllowed", JSONArray().apply { windowAllowed.forEach { put(it) } })
        .put("windowShortApps", JSONArray().apply { windowShortApps.forEach { put(it) } })
        .put("windowShortMinutes", windowShortMinutes)
        .put("windowPauseUntil", windowPauseUntil)
        .put("lookIntervalMinutes", lookIntervalMinutes)
        .put("setSize", setSize)
        .put("restMinutes", restMinutes)
        .put("baseSets", baseSets)
        .put("themeMode", themeMode)
        .put("shakeToRoll", shakeToRoll)
        .put("manualWindowStart", manualWindowStart)
        .put("windowStoppedAt", windowStoppedAt)
        .put("manualChecks", JSONArray().apply { manualChecks.forEach { put(it) } })
        .put("pullBackEnabled", pullBackEnabled)
        .put("pullBackMinutes", pullBackMinutes)
        .put("idleHours", idleHours)
        .put("voiceMode", voiceMode)
        .put("voiceAutoSendSeconds", voiceAutoSendSeconds)
        .put("timeZone", timeZone)
        .put("haptics", haptics)
        .put("coachSetMoves", coachSetMoves)
        .put("setCards", setCards)
        .put("gateCard", gateCard)
        .put("gateMode", gateMode)
        .put("talkLevel", talkLevel)
        .put("predictFirst", predictFirst)
        .put("bankLinks", bankLinks)
        .put("drillApps", drillApps)
        .put("pendingExit", pendingExit)
        .put("pendingExitAt", pendingExitAt)
        .put("warmup", warmup)
        .put("faceLevels", JSONObject().apply { faceLevels.forEach { (k, v) -> put(k.toString(), v) } })
        .toString()

    companion object {
        const val DEFAULT_PASS_MINUTES = 10
        const val MIN_PASS_MINUTES = 5
        const val MAX_PASS_MINUTES = 30

        val DEFAULT_GROUPS = listOf(AppGroup("短视频与社区", 10), AppGroup("游戏", 10))

        /** 默认可以短暂切过去的：微信、QQ */
        val DEFAULT_SHORT_APPS = setOf("com.tencent.mm", "com.tencent.mobileqq")

        private fun JSONObject.stringSet(key: String): Set<String>? = optJSONArray(key)?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toSet()
        }

        /** 默认关注：抖音、B站、贴吧、三角洲行动。没装的应用不影响。 */
        val DEFAULT_WATCHED = mapOf(
            "com.ss.android.ugc.aweme" to "短视频与社区",
            "tv.danmaku.bili" to "短视频与社区",
            "com.baidu.tieba" to "短视频与社区",
            "com.tencent.tmgp.dfm" to "游戏",
        )

        /** 读不出来（空、损坏）时返回默认设置，不让应用崩溃。 */
        fun fromJson(text: String?): AppPrefs {
            if (text.isNullOrBlank()) return AppPrefs(
                recallDay = o.optLong("recallDay", Long.MIN_VALUE),
                recallIds = o.optJSONArray("recallIds")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet(),)
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
            val checks = o.stringSet("manualChecks") ?: d.manualChecks
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
                nudgeEnabled = o.optBoolean("nudgeEnabled", d.nudgeEnabled),
                nudgeHour = o.optInt("nudgeHour", d.nudgeHour).coerceIn(0, 23),
                nudgeMinute = o.optInt("nudgeMinute", d.nudgeMinute).coerceIn(0, 59),
                windowStrict = o.optBoolean("windowStrict", d.windowStrict),
                windowAllowed = o.stringSet("windowAllowed") ?: d.windowAllowed,
                windowShortApps = o.stringSet("windowShortApps") ?: d.windowShortApps,
                windowShortMinutes = o.optInt("windowShortMinutes", d.windowShortMinutes).coerceIn(1, 15),
                windowPauseUntil = o.optLong("windowPauseUntil", 0),
                lookIntervalMinutes = o.optInt("lookIntervalMinutes", d.lookIntervalMinutes).coerceIn(0, 30),
                setSize = o.optInt("setSize", d.setSize).coerceIn(1, 10),
                restMinutes = o.optInt("restMinutes", d.restMinutes).coerceIn(1, 15),
                baseSets = o.optInt("baseSets", d.baseSets).coerceIn(1, 8),
                themeMode = o.optInt("themeMode", d.themeMode).coerceIn(0, 2),
                shakeToRoll = o.optBoolean("shakeToRoll", d.shakeToRoll),
                manualWindowStart = o.optLong("manualWindowStart", 0),
                windowStoppedAt = o.optLong("windowStoppedAt", 0),
                manualChecks = checks,
                pullBackEnabled = o.optBoolean("pullBackEnabled", d.pullBackEnabled),
                pullBackMinutes = o.optInt("pullBackMinutes", d.pullBackMinutes).coerceIn(1, 15),
                idleHours = o.optInt("idleHours", d.idleHours).coerceIn(0, 12),
                voiceMode = o.optBoolean("voiceMode", d.voiceMode),
                voiceAutoSendSeconds = o.optInt("voiceAutoSendSeconds", d.voiceAutoSendSeconds).coerceIn(0, 10),
                timeZone = o.optString("timeZone", d.timeZone).ifBlank { d.timeZone },
                haptics = o.optBoolean("haptics", d.haptics),
                coachSetMoves = o.optInt("coachSetMoves", d.coachSetMoves).coerceIn(3, 8),
                setCards = o.optInt("setCards", d.setCards).coerceIn(1, 10),
                gateCard = o.optBoolean("gateCard", d.gateCard),
                gateMode = o.optString("gateMode", if (o.has("gateCard") && !o.optBoolean("gateCard", true)) "check" else d.gateMode),
                talkLevel = o.optString("talkLevel", d.talkLevel),
                predictFirst = o.optBoolean("predictFirst", d.predictFirst),
                // 旧版默认的网页链接换成新的“App + 网址”列表；改过的保留
                bankLinks = o.optString("bankLinks", d.bankLinks).let { if (it == com.yishou.app.bank.BankPrompt.DEFAULT_LINKS) d.bankLinks else it },
                drillApps = o.optString("drillApps", d.drillApps),
                pendingExit = o.optString("pendingExit", ""),
                pendingExitAt = o.optLong("pendingExitAt", 0),
                warmup = o.optBoolean("warmup", d.warmup),
                faceLevels = o.optJSONObject("faceLevels")?.let { f ->
                    f.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.takeIf { it in 1..6 }?.let { it to f.optInt(k).coerceIn(0, 3) } }.toMap()
                } ?: d.faceLevels,
            )
        }
    }
}
