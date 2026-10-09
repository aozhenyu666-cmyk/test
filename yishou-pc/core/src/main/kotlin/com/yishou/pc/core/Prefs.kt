package com.yishou.pc.core

import org.json.JSONArray
import org.json.JSONObject

/** 一类要守的东西：每天一共能用多久，一次最多放多久。 */
data class Category(
    val id: String,
    val name: String,
    /** 每天额度（分钟） */
    val dailyMinutes: Int,
    /** 一次最多放行几分钟 */
    val maxPass: Int,
)

enum class RuleKind {
    /** 程序名，例如 WeGame.exe */
    PROCESS,
    /** 浏览器窗口标题里含这个词，例如“哔哩哔哩” */
    TITLE,
    /** 由这个程序启动的程序，例如 WeGame 启动的游戏 */
    CHILD_OF,
}

data class Rule(val kind: RuleKind, val pattern: String, val category: String)

/** 每天固定的专注时段：从 hour:minute 开始，持续 minutes 分钟。 */
data class FocusSlot(val hour: Int, val minute: Int, val minutes: Int)

/**
 * 电脑版的全部设置，整体存成一段 JSON；缺少的项用默认值，以后加设置项时旧数据也能读。
 */
data class Prefs(
    val categories: List<Category> = DEFAULT_CATEGORIES,
    val rules: List<Rule> = DEFAULT_RULES,
    /** 按标题认网站时，只看这些浏览器的窗口（小写程序名） */
    val browsers: Set<String> = DEFAULT_BROWSERS,
    val focusSlots: List<FocusSlot> = emptyList(),
    /** 手动开始专注时的默认时长 */
    val focusMinutes: Int = 45,
    /** 手动开始的专注时段的开始时间与时长，0 表示没有 */
    val manualFocusStart: Long = 0,
    val manualFocusMinutes: Int = 0,
    /** 最近一次提前结束专注的时间 */
    val focusStoppedAt: Long = 0,
    /** 朗读提醒（Windows 自带语音） */
    val speak: Boolean = true,
    /** 开机自动启动 */
    val autostart: Boolean = true,
    /** 每天“真有急事”的次数 */
    val emergencyPerDay: Int = 2,
    /** 冷静期：第一次几秒，之后每次加几秒，最多几秒 */
    val cooldownBase: Int = 10,
    val cooldownStep: Int = 10,
    val cooldownMax: Int = 60,
    /** 看过第一次的说明 */
    val welcomed: Boolean = false,
) {
    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    fun toJson(): String = JSONObject()
        .put("categories", JSONArray().apply {
            categories.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("daily", it.dailyMinutes).put("max", it.maxPass))
            }
        })
        .put("rules", JSONArray().apply {
            rules.forEach { put(JSONObject().put("kind", it.kind.name).put("pattern", it.pattern).put("category", it.category)) }
        })
        .put("browsers", JSONArray(browsers.sorted()))
        .put("focusSlots", JSONArray().apply {
            focusSlots.forEach { put(JSONObject().put("h", it.hour).put("m", it.minute).put("minutes", it.minutes)) }
        })
        .put("focusMinutes", focusMinutes)
        .put("manualFocusStart", manualFocusStart)
        .put("manualFocusMinutes", manualFocusMinutes)
        .put("focusStoppedAt", focusStoppedAt)
        .put("speak", speak)
        .put("autostart", autostart)
        .put("emergencyPerDay", emergencyPerDay)
        .put("cooldownBase", cooldownBase)
        .put("cooldownStep", cooldownStep)
        .put("cooldownMax", cooldownMax)
        .put("welcomed", welcomed)
        .toString(2)

    companion object {
        const val WEB = "web"
        const val GAME = "game"
        const val EMU = "emu"

        val DEFAULT_CATEGORIES = listOf(
            Category(WEB, "网页娱乐", dailyMinutes = 40, maxPass = 15),
            Category(GAME, "游戏", dailyMinutes = 60, maxPass = 30),
            Category(EMU, "模拟器", dailyMinutes = 30, maxPass = 15),
        )

        val DEFAULT_BROWSERS = setOf(
            "quark.exe", "chrome.exe", "msedge.exe", "firefox.exe", "360se.exe", "360chrome.exe",
            "qqbrowser.exe", "sogouexplorer.exe", "2345explorer.exe", "liebao.exe", "brave.exe", "opera.exe",
        )

        /** 默认按标题认的网站：视频、短剧、社区、小说 */
        val DEFAULT_SITES = listOf(
            "哔哩哔哩", "bilibili", "抖音", "贴吧", "微博", "快手", "小红书", "虎牙", "斗鱼", "西瓜视频",
            "爱奇艺", "优酷", "腾讯视频", "芒果TV", "红果", "番茄小说", "起点中文网",
        )

        val DEFAULT_RULES: List<Rule> =
            DEFAULT_SITES.map { Rule(RuleKind.TITLE, it, WEB) } + listOf(
                Rule(RuleKind.PROCESS, "wegame.exe", GAME),
                Rule(RuleKind.CHILD_OF, "wegame.exe", GAME),
                // 雷电模拟器：主窗口和多开器
                Rule(RuleKind.PROCESS, "dnplayer.exe", EMU),
                Rule(RuleKind.PROCESS, "dnmultiplayer.exe", EMU),
            )

        /** 读不出来（空、损坏）时返回默认设置。 */
        fun fromJson(text: String?): Prefs {
            if (text.isNullOrBlank()) return Prefs()
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                return Prefs()
            }
            val d = Prefs()
            val categories = o.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { c ->
                        val id = c.optString("id").trim()
                        if (id.isEmpty()) null
                        else Category(
                            id,
                            c.optString("name", id).ifBlank { id },
                            c.optInt("daily", 30).coerceIn(0, 600),
                            c.optInt("max", 15).coerceIn(1, 180),
                        )
                    }
                }.distinctBy { it.id }
            }?.takeIf { it.isNotEmpty() } ?: d.categories
            val ids = categories.map { it.id }.toSet()
            val rules = o.optJSONArray("rules")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { r ->
                        val kind = runCatching { RuleKind.valueOf(r.optString("kind")) }.getOrNull()
                        val pattern = r.optString("pattern").trim()
                        val cat = r.optString("category")
                        if (kind == null || pattern.isEmpty() || cat !in ids) null else Rule(kind, pattern, cat)
                    }
                }.distinct()
            } ?: d.rules
            val browsers = o.optJSONArray("browsers")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            } ?: d.browsers
            val slots = o.optJSONArray("focusSlots")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        FocusSlot(it.optInt("h").coerceIn(0, 23), it.optInt("m").coerceIn(0, 59), it.optInt("minutes", 45).coerceIn(5, 240))
                    }
                }
            } ?: d.focusSlots
            return Prefs(
                categories = categories,
                rules = rules,
                browsers = browsers,
                focusSlots = slots,
                focusMinutes = o.optInt("focusMinutes", d.focusMinutes).coerceIn(5, 240),
                manualFocusStart = o.optLong("manualFocusStart", 0),
                manualFocusMinutes = o.optInt("manualFocusMinutes", 0).coerceIn(0, 240),
                focusStoppedAt = o.optLong("focusStoppedAt", 0),
                speak = o.optBoolean("speak", d.speak),
                autostart = o.optBoolean("autostart", d.autostart),
                emergencyPerDay = o.optInt("emergencyPerDay", d.emergencyPerDay).coerceIn(0, 5),
                cooldownBase = o.optInt("cooldownBase", d.cooldownBase).coerceIn(0, 120),
                cooldownStep = o.optInt("cooldownStep", d.cooldownStep).coerceIn(0, 120),
                cooldownMax = o.optInt("cooldownMax", d.cooldownMax).coerceIn(0, 300),
                welcomed = o.optBoolean("welcomed", false),
            )
        }
    }
}
