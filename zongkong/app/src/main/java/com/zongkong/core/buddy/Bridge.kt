package com.zongkong.core.buddy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 外接大脑：把「此刻正在发生什么」推给电脑上的 DeepSeek Harness（DSH）里的伙伴。
 *
 * 为什么：总控是事后的——你出了问题它才提醒。伙伴要在事情发生的当下就能聊：
 * 你刚解锁、刚点开抖音、已经刷了 30 分钟、一手刚有题没接住——这些时刻手机这边看得到，
 * 但真正会聊天、有记忆、有心情的那个「大肥鱼」住在 DSH 里，并且通过 QQ 找你（QQ 手机不会杀）。
 * 所以手机只负责「看到了就告诉它」，说不说、怎么说由 DSH 那边决定。
 *
 * 这里是纯逻辑：给上一刻的状态和这一刻的情况，算出要推哪些事件。不碰 Android、不联网，单元测试直接跑。
 * 接收端的约定写在 zongkong/docs/伙伴-DSH部署报告.md。
 */
@Serializable
data class BridgeConfig(
    val enabled: Boolean = false,
    /** DSH 里桥接插件的地址，例如 http://192.168.1.5:7860/zongkong/event */
    val url: String = "",
    /** 共享口令：放在 Authorization: Bearer <token> */
    val token: String = "",
    /** 开着外接大脑时，手机里的小伙伴不再自己主动开口（免得两个声音） */
    val muteLocal: Boolean = true,
)

/** 这一刻手机看到的。 */
data class Snapshot(
    val now: Long,
    val pkg: String = "",
    val appName: String = "",
    /** 这个应用从什么时候开始在前台 */
    val since: Long = 0,
    /** 是不是娱乐应用（在总控的拦截名单里） */
    val play: Boolean = false,
    val unlockedAt: Long = 0,
    val strict: Boolean = false,
    val headline: String = "",
    val mainTask: String = "",
    val step: String = "",
    val yishouLine: String = "",
    /** 一手最近一次没接住的时刻和描述 */
    val missAt: Long = 0,
    val miss: String = "",
    val screenOn: Boolean = true,
)

/** 上一次推过什么（防重复）。 */
data class BridgeState(
    val pkg: String = "",
    val since: Long = 0,
    val unlockedAt: Long = 0,
    val missAt: Long = 0,
    /** null = 还没观察过（第一次不当成「变化」） */
    val strict: Boolean? = null,
    /** 这一段娱乐已经推过的分钟阈值 */
    val bingeSent: Set<Int> = emptySet(),
)

data class BridgeEvent(val kind: String, val text: String)

object Bridge {
    /** 连续刷到这些分钟数时各推一次 */
    val BINGE = listOf(15, 30, 60, 90, 120)

    const val UNLOCK = "unlock"
    const val APP_ENTER = "app_enter"
    const val BINGE_KIND = "binge"
    const val MISS = "yishou_miss"
    const val STRICT_ON = "strict_on"
    const val STRICT_OFF = "strict_off"
    const val TEST = "test"

    fun events(st: BridgeState, s: Snapshot): Pair<List<BridgeEvent>, BridgeState> {
        val out = mutableListOf<BridgeEvent>()
        var next = st

        // 只推「刚刚」的：进程重启后不把老事件再推一遍
        if (s.unlockedAt > st.unlockedAt && s.now - s.unlockedAt < 2 * 60_000L) {
            out += BridgeEvent(UNLOCK, "他刚解锁手机" + if (s.mainTask.isNotBlank()) "（今天想做成的事：${s.mainTask}）" else "")
        }
        next = next.copy(unlockedAt = maxOf(st.unlockedAt, s.unlockedAt))

        val newSegment = s.pkg != st.pkg || s.since != st.since
        if (newSegment) {
            if (s.play && s.pkg.isNotBlank() && s.screenOn) out += BridgeEvent(APP_ENTER, "他刚打开「${s.appName.ifBlank { s.pkg }}」")
            next = next.copy(pkg = s.pkg, since = s.since, bingeSent = emptySet())
        }

        if (s.play && s.screenOn && s.since > 0) {
            val minutes = ((s.now - s.since) / 60_000).toInt()
            val crossed = BINGE.filter { it <= minutes && it !in next.bingeSent }
            if (crossed.isNotEmpty()) {
                val m = crossed.max()
                out += BridgeEvent(BINGE_KIND, "他已经在「${s.appName.ifBlank { s.pkg }}」里连续 $m 分钟了")
                next = next.copy(bingeSent = next.bingeSent + crossed)
            }
        }

        if (s.missAt > st.missAt && s.miss.isNotBlank()) {
            if (s.now - s.missAt < 30 * 60_000L) out += BridgeEvent(MISS, "他在一手刷题时这一题没接住：${s.miss}")
            next = next.copy(missAt = s.missAt)
        }

        if (st.strict != null && st.strict != s.strict) {
            out += if (s.strict) BridgeEvent(STRICT_ON, "总控刚把他转进严管：${s.headline}") else BridgeEvent(STRICT_OFF, "总控刚解除严管")
        }
        next = next.copy(strict = s.strict)
        return out to next
    }

    /** 推给 DSH 的一条：事件 + 此刻的情况（DSH 那边拿它当背景，自己决定说不说）。 */
    fun payload(e: BridgeEvent, s: Snapshot): JsonObject = buildJsonObject {
        put("v", 1)
        put("source", "zongkong")
        put("kind", e.kind)
        put("at", s.now)
        put("text", e.text)
        putJsonObject("app") {
            put("pkg", s.pkg)
            put("name", s.appName)
            put("minutes", if (s.since > 0) ((s.now - s.since) / 60_000).toInt() else 0)
            put("play", s.play)
        }
        putJsonObject("day") {
            put("mainTask", s.mainTask)
            put("step", s.step)
            put("yishou", s.yishouLine)
        }
        putJsonObject("zongkong") {
            put("strict", s.strict)
            put("headline", s.headline)
        }
        put("screenOn", s.screenOn)
    }
}
