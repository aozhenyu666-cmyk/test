package huilu.app

import android.content.Context
import huilu.core.Codec
import huilu.core.EnvMode
import huilu.core.Event
import huilu.core.EventLog
import huilu.core.Level
import huilu.core.Settings
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 事件日志：一行一个 JSON，只追加，每次写入后 fsync。
 * 写到一半断电留下的残行在读取时被跳过，并在下次追加前补上换行，不会污染后面的记录。
 */
class FileLog(dir: File) : EventLog {
    val file = File(dir, "events.jsonl")

    init {
        if (file.exists() && file.length() > 0) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 1)
                if (raf.read() != '\n'.code) FileOutputStream(file, true).use { it.write('\n'.code) }
            }
        }
    }

    @Synchronized
    override fun append(e: Event) {
        FileOutputStream(file, true).use {
            it.write((Codec.encode(e) + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    @Synchronized
    override fun readAll(): List<Event> =
        if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).mapNotNull { if (it.isBlank()) null else Codec.decode(it) }
}

/** 用户设置。只存在本机。 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("huilu", Context.MODE_PRIVATE)

    fun settings(): Settings {
        val d = Settings()
        return d.copy(
            distractors = sp.getStringSet("distractors", null)?.toSet() ?: d.distractors,
            driftThresholdSec = sp.getInt("driftSec", d.driftThresholdSec),
            maxLevel = Level.values().firstOrNull { it.name == sp.getString("maxLevel", null) } ?: d.maxLevel,
            renotifyMin = sp.getInt("renotifyMin", d.renotifyMin),
            giveUpMin = sp.getInt("giveUpMin", d.giveUpMin),
        )
    }

    var distractors: Set<String>
        get() = settings().distractors
        set(v) = sp.edit().putStringSet("distractors", v).apply()
    var driftSec: Int
        get() = settings().driftThresholdSec
        set(v) = sp.edit().putInt("driftSec", v.coerceIn(30, 1800)).apply()
    var renotifyMin: Int
        get() = settings().renotifyMin
        set(v) = sp.edit().putInt("renotifyMin", v.coerceIn(1, 30)).apply()
    var giveUpMin: Int
        get() = settings().giveUpMin
        set(v) = sp.edit().putInt("giveUpMin", v.coerceIn(2, 60)).apply()
    var maxLevel: Level
        get() = settings().maxLevel
        set(v) = sp.edit().putString("maxLevel", v.name).apply()

    // 上一次开始行动时的选择，下次默认沿用
    var lastMinutes: Int
        get() = sp.getInt("lastMinutes", 10)
        set(v) = sp.edit().putInt("lastMinutes", v).apply()
    var lastMode: EnvMode
        get() = EnvMode.values().firstOrNull { it.name == sp.getString("lastMode", null) } ?: EnvMode.ANY
        set(v) = sp.edit().putString("lastMode", v.name).apply()
    var lastTargets: Set<String>
        get() = sp.getStringSet("lastTargets", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("lastTargets", v).apply()
    var lastStrict: Boolean
        get() = sp.getBoolean("lastStrict", false)
        set(v) = sp.edit().putBoolean("lastStrict", v).apply()

    // AI 判断层（OpenAI 兼容接口）
    var llmEnabled: Boolean
        get() = sp.getBoolean("llmEnabled", false)
        set(v) = sp.edit().putBoolean("llmEnabled", v).apply()
    var llmBase: String
        get() = sp.getString("llmBase", "https://api.deepseek.com")!!
        set(v) = sp.edit().putString("llmBase", v.trim().trimEnd('/')).apply()
    var llmModel: String
        get() = sp.getString("llmModel", "deepseek-chat")!!
        set(v) = sp.edit().putString("llmModel", v.trim()).apply()
    var llmKey: String
        get() = sp.getString("llmKey", "")!!
        set(v) = sp.edit().putString("llmKey", v.trim()).apply()
    val llmReady: Boolean get() = llmEnabled && llmKey.isNotBlank() && llmBase.isNotBlank()
}
