package com.yishou.app.stats

import android.content.Context
import com.yishou.app.llm.UsageSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 盘点数据：每天一条 JSON，存在普通 SharedPreferences 里（只是计数，不含任何内容），
 * 只保留最近 30 天。
 */
class StatsStore(context: Context) : UsageSink {

    private val prefs = context.applicationContext.getSharedPreferences("yishou_stats", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** 每次有新记录就加一，页面据此刷新 */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized
    override fun record(kind: String, ok: Boolean, promptTokens: Int, completionTokens: Int, millis: Long, requestChars: Int) {
        update { it.plusUsage(kind, ok, promptTokens, completionTokens, millis, requestChars) }
    }

    @Synchronized
    fun event(name: String) {
        update { it.plusEvent(name) }
    }

    fun day(date: LocalDate): DayStats = DayStats.fromJson(prefs.getString(key(date), null), date.toString())

    fun lastDays(n: Int): List<DayStats> {
        val today = LocalDate.now()
        return (0 until n).map { day(today.minusDays(it.toLong())) }
    }

    private fun update(change: (DayStats) -> DayStats) {
        val today = LocalDate.now()
        val next = change(day(today))
        val edit = prefs.edit().putString(key(today), next.toJson())
        prefs.all.keys
            .filter { it.startsWith(PREFIX) && it < key(today.minusDays(30)) }
            .forEach { edit.remove(it) }
        edit.apply()
        _version.value++
    }

    private fun key(date: LocalDate) = PREFIX + date

    companion object {
        private const val PREFIX = "day_"
    }
}
