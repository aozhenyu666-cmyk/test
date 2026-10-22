package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.core.probe.DailyBudget
import java.time.LocalDate

/** 按天统计：开口、回应、忽略、进入 Gemini（文字 / Live 分开），以及当天调用次数。 */
class Stats(context: Context) {
    enum class Kind(val key: String, val label: String) {
        SPOKE("spoke", "开口"),
        REPLIED("replied", "回应"),
        IGNORED("ignored", "忽略"),
        GEMINI_TEXT("gemini_text", "文字深聊"),
        GEMINI_LIVE("gemini_live", "Live 共看"),
        API_CALLS("api", "调用"),
        WEIGHTY("weighty", "有分量的开口"),
    }

    private val prefs = context.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)

    fun add(kind: Kind, day: LocalDate = LocalDate.now()) {
        val k = "$day.${kind.key}"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    fun get(kind: Kind, day: LocalDate = LocalDate.now()): Int = prefs.getInt("$day.${kind.key}", 0)

    /** 最近有记录的若干天，新的在前。 */
    fun days(limit: Int = 14): List<LocalDate> =
        prefs.all.keys.mapNotNull { runCatching { LocalDate.parse(it.substringBefore('.')) }.getOrNull() }
            .toSortedSet().reversed().take(limit)

    /** 从统计里恢复当天调用预算，进程重启后不重置。 */
    fun budget(limit: Int, day: LocalDate = LocalDate.now()) = DailyBudget(limit, day.toString(), get(Kind.API_CALLS, day))
}
