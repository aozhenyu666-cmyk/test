package com.zongkong.app.data

import android.content.Context
import android.util.Log
import com.zongkong.core.Config
import com.zongkong.core.DayClock
import com.zongkong.core.DayLog
import com.zongkong.core.Engine
import com.zongkong.core.Policy
import com.zongkong.core.Status
import com.zongkong.core.ZkJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * 本机存储：config.json 一份配置，days/yyyy-MM-dd.json 每天一份记录。
 * 修改在内存里同步完成（加锁），写文件放到后台单线程，写入时先写临时文件再改名，避免写坏。
 */
class Store(private val context: Context) {
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val dir = File(context.filesDir, "zk").apply { mkdirs() }
    private val daysDir = File(dir, "days").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("zk", Context.MODE_PRIVATE)

    val zone: ZoneId get() = ZoneId.systemDefault()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _today = MutableStateFlow(loadDay(DayClock.dateKey(System.currentTimeMillis(), zone)))
    val today: StateFlow<DayLog> = _today.asStateFlow()

    /** 换日、到点的放宽改动都在这里处理。每次算状态前调用。 */
    fun refresh(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val key = DayClock.dateKey(now, zone)
            if (_today.value.date != key) _today.value = loadDay(key)
            val c = _config.value
            val applied = Policy.applyDue(c, now)
            if (applied != c) setConfigLocked(applied)
        }
    }

    fun status(now: Long = System.currentTimeMillis()): Status {
        refresh(now)
        return Engine.evaluate(_config.value, _today.value, now, zone)
    }

    fun updateConfig(f: (Config) -> Config): Config = synchronized(lock) {
        val next = f(_config.value)
        if (next != _config.value) setConfigLocked(next)
        next
    }

    private fun setConfigLocked(c: Config) {
        _config.value = c
        val text = ZkJson.encodeToString(Config.serializer(), c)
        io.execute { writeAtomic(File(dir, "config.json"), text) }
    }

    /** 修改今天（逻辑日）的记录。 */
    fun updateToday(now: Long = System.currentTimeMillis(), f: (DayLog) -> DayLog): DayLog = synchronized(lock) {
        refresh(now)
        val next = f(_today.value)
        if (next != _today.value) {
            _today.value = next
            saveDay(next)
        }
        next
    }

    /** 修改某一天的记录（同步旧记录用）。 */
    fun updateDay(date: String, f: (DayLog) -> DayLog) = synchronized(lock) {
        if (date == _today.value.date) {
            updateToday(f = f)
        } else {
            val next = f(loadDay(date))
            saveDay(next)
        }
    }

    private fun saveDay(day: DayLog) {
        val text = ZkJson.encodeToString(DayLog.serializer(), day)
        io.execute { writeAtomic(File(daysDir, "${day.date}.json"), text) }
    }

    fun day(date: String): DayLog = synchronized(lock) {
        if (date == _today.value.date) _today.value else loadDay(date)
    }

    /** 最近 n 天（含今天）的记录，新的在前。 */
    fun history(n: Int): List<DayLog> {
        val today = LocalDate.parse(_today.value.date)
        return (0 until n).map { day(today.minusDays(it.toLong()).toString()) }
    }

    // ---------- 小状态：心跳、草稿、提醒去重 ----------

    var heartbeat: Long
        get() = prefs.getLong("heartbeat", 0L)
        set(v) = prefs.edit().putLong("heartbeat", v).apply()

    var syncNote: String
        get() = prefs.getString("sync_note", "") ?: ""
        set(v) = prefs.edit().putString("sync_note", v).apply()

    fun draft(gateId: String): String? = prefs.getString("draft_$gateId", null)
    fun saveDraft(gateId: String, text: String) = prefs.edit().putString("draft_$gateId", text).apply()
    fun clearDraft(gateId: String) = prefs.edit().remove("draft_$gateId").apply()

    /** 某条提醒今天发过没有。发过返回 false，没发过记下并返回 true。 */
    fun firstAlert(key: String): Boolean = synchronized(lock) {
        val date = _today.value.date
        val stored = prefs.getString("alerts", "") ?: ""
        val (d, keys) = stored.split('|', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val set = if (d == date) keys.split(',').filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
        if (!set.add(key)) return false
        prefs.edit().putString("alerts", date + "|" + set.joinToString(",")).apply()
        true
    }

    // ---------- 文件 ----------

    private fun loadConfig(): Config {
        val f = File(dir, "config.json")
        if (!f.exists()) return Config()
        return try {
            ZkJson.decodeFromString(Config.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "配置文件读不了，备份后用默认配置", e)
            f.copyTo(File(dir, "config.broken.${System.currentTimeMillis()}.json"), overwrite = true)
            Config()
        }
    }

    private fun loadDay(date: String): DayLog {
        val f = File(daysDir, "$date.json")
        if (!f.exists()) return DayLog(date)
        return try {
            ZkJson.decodeFromString(DayLog.serializer(), f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "记录 $date 读不了", e)
            DayLog(date)
        }
    }

    private fun writeAtomic(target: File, text: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入 ${target.name} 失败", e)
        }
    }

    companion object {
        private const val TAG = "Store"
    }
}
