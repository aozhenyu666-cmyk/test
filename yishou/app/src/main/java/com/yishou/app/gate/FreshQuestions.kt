package com.yishou.app.gate

import com.yishou.app.YishouApp
import com.yishou.app.judge.LockedJson
import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Locks
import com.yishou.app.log.RunLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/**
 * 题库没题时，入口现出一道题：模型先出题、本机检查判据不能从题面抄出来，锁好了才给人看。
 * 用过的素材池里随便挑一段；池子空着就出一道日常决策题。
 * 备题存在私有文件里，下一次打开不用等。出不来就是出不来：返回 null，入口走默写或等待，不拿没锁的题凑数。
 */
object FreshQuestions {
    private const val KEEP = 2
    private val lock = Mutex()

    private fun file(app: YishouApp) = File(app.filesDir, "gate_fresh.json")

    private fun read(app: YishouApp): MutableList<LockedQ> = runCatching {
        val a = JSONArray(file(app).readText())
        (0 until a.length()).map { LockedJson.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun write(app: YishouApp, list: List<LockedQ>) {
        runCatching { file(app).writeText(JSONArray(list.map(LockedJson::toJson)).toString()) }
            .onFailure { RunLog.e("入口现出题", "备题没写进去", it) }
    }

    /** 取一道：先拿备好的，没有就现出 */
    suspend fun take(app: YishouApp): LockedQ? {
        lock.withLock {
            val list = read(app)
            if (list.isNotEmpty()) {
                val q = list.removeAt(0)
                write(app, list)
                return q
            }
        }
        return generate(app)
    }

    /** 后台补到两道 */
    suspend fun prefetch(app: YishouApp) {
        if (!app.settings.llm.value.isComplete) return
        repeat(KEEP) {
            if (lock.withLock { read(app).size } >= KEEP) return
            val q = generate(app) ?: return
            lock.withLock { write(app, (read(app) + q).takeLast(KEEP)) }
        }
    }

    suspend fun generate(app: YishouApp): LockedQ? {
        if (!app.settings.llm.value.isComplete) return null
        val material = runCatching { app.database.everyday().materials().first() }.getOrDefault(emptyList())
            .filter { it.text.isNotBlank() }.randomOrNull()?.text
        val user = if (material != null) Locks.genUser(material) else NO_MATERIAL
        val ask = app.asker("入口出题")
        repeat(2) {
            val reply = ask(Locks.GEN_SYSTEM, user) ?: return@repeat
            Locks.parseGen("fresh-" + System.currentTimeMillis(), reply)?.let { return it }
        }
        return null
    }

    private const val NO_MATERIAL =
        "没有素材。出一道日常生活里的决策或推理题：给一个具体的小情境（时间、地点、人、数字），问该怎么判断或怎么取舍。"
}
