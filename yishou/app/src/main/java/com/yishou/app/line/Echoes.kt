package com.yishou.app.line

import com.yishou.app.data.Echo
import com.yishou.app.think.Words

/**
 * 回响队列怎么挑：开局那一刻推哪一条到你面前。
 * - 同一个场景（同一个 App）的差值优先：在扣扳机前接通上次的修正；
 * - 刚确认过的先歇着：确认过几次，就隔几档时间再推（半天、1 天、3 天、7 天）；
 * - 都歇着时，不推（不拿旧东西烦你）。
 */
object Echoes {

    private const val HOUR = 3_600_000L
    val REST = listOf(12 * HOUR, 24 * HOUR, 72 * HOUR, 168 * HOUR)

    fun due(e: Echo, now: Long): Boolean {
        val last = e.shownAt ?: return true
        val rest = REST[e.confirmed.coerceIn(0, REST.lastIndex)]
        return now - last >= rest
    }

    fun pick(all: List<Echo>, scene: String, now: Long): Echo? {
        val ready = all.filter { due(it, now) }
        if (scene.isNotEmpty()) ready.filter { it.scene == scene }.maxByOrNull { it.createdAt }?.let { return it }
        // 没有这个场景的：挑确认得最少、最新的那条
        return ready.sortedWith(compareBy<Echo> { it.confirmed }.thenByDescending { it.createdAt }).firstOrNull()
    }

    /** 差值写得够不够用：至少说出“差在哪”和“下次先做什么”里的一样，去掉口水 6 个字以上 */
    fun usable(text: String): Boolean = Words.substance(text) >= 6

    /** 差值的提示模板 */
    const val DELTA_HINT = "本来想……，漏看了……，下次见到……先……"

    /** 读一遍算确认：你说的和原文对上一半以上 */
    fun readBack(original: String, said: String): Boolean {
        if (said.isBlank()) return false
        val r = com.yishou.app.think.Recite.diff(original, said)
        return r.total > 0 && r.matched * 2 >= r.total
    }
}
