package com.zongkong.app.director

import android.content.Context
import com.zongkong.app.window.Relay
import com.zongkong.core.DayClock
import com.zongkong.core.director.Card

/**
 * 打扰闸门（v4 第 4 节）。
 *
 * 只回答一个问题：**这一张卡，现在该弹出来吗？**
 *
 * 规则的分工很关键：
 *  - **用户自己点出来的卡**（回应、收线）永远显示——那是他正在参与的对话；
 *  - **事件推出来的卡**（过点、娱乐、TICK、夜里）必须过这里，不通过就只留在议程里，等用户点小圆点自己看。
 *
 * 之前“墨灵不断跳出”的根因就在这：事件一来就 present，收线完还接着弹回头看的卡。
 */
object Quiet {
    private const val PREFS = "mailuo_quiet"
    private const val MAX_PER_DAY = 6
    private const val NIGHT_GAP_MIN = 30
    private const val SAME_CARD_MIN = 10
    private const val MIN_GAP_MIN = 30

    @Volatile private var lastSessionEnd = 0L
    @Volatile private var lastUserAt = 0L
    @Volatile private var lastShownAt = 0L
    @Volatile private var lastShownKey = ""
    @Volatile private var lastShownTopic = ""

    /** 用户回应过 / 收线了：记下来，接下来一段时间别主动出声。 */
    fun noteUser(now: Long) {
        lastUserAt = now
    }

    fun noteSessionEnd(now: Long) {
        lastSessionEnd = now
    }

    fun noteShown(card: Card, now: Long) {
        lastShownAt = now
        lastShownKey = keyOf(card)
        lastShownTopic = card.batonId.ifBlank { card.title }
    }

    /** 事件驱动的卡能不能弹。 */
    fun allow(context: Context, card: Card, now: Long): Boolean = when {
        // 正在和演员聊：一张都不弹，全排队
        Relay.active() -> false
        // 收线后 30 分钟：让她安静下来（这一段以前最吵）
        lastSessionEnd > 0 && now - lastSessionEnd < NIGHT_GAP_MIN * 60_000L -> false
        // 用户刚动过：3 分钟内不插话
        lastUserAt > 0 && now - lastUserAt < 3 * 60_000L -> false
        // 深夜只排队
        night(now) -> false
        // 同一张卡 10 分钟内不重复
        keyOf(card) == lastShownKey && now - lastShownAt < SAME_CARD_MIN * 60_000L -> false
        // 同一件事 30 分钟内不重复
        topicOf(card) == lastShownTopic && now - lastShownAt < MIN_GAP_MIN * 60_000L -> false
        // 每天上限
        used(context, now) >= MAX_PER_DAY -> false
        else -> true
    }

    /** 弹出去以后记账（按天）。 */
    fun record(context: Context, now: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = DayClock.dateKey(now, DayClock.BEIJING)
        val n = if ((p.getString("day", "") ?: "") == day) p.getInt("n", 0) + 1 else 1
        p.edit().putString("day", day).putInt("n", n).apply()
    }

    fun used(context: Context, now: Long): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = DayClock.dateKey(now, DayClock.BEIJING)
        return if ((p.getString("day", "") ?: "") == day) p.getInt("n", 0) else 0
    }

    /** 给设置页 / 调试看的一句话。 */
    fun status(context: Context, now: Long): String =
        "今天主动出声 ${used(context, now)}/$MAX_PER_DAY 次" + if (Relay.active()) " · 正在陪练（不打扰）" else ""

    private fun night(now: Long): Boolean {
        val t = java.time.Instant.ofEpochMilli(now).atZone(DayClock.BEIJING)
        val m = t.hour * 60 + t.minute
        return m >= 23 * 60 + 30 || m < 7 * 60 + 30
    }

    private fun keyOf(card: Card): String = card.ask.take(24)

    private fun topicOf(card: Card): String = card.batonId.ifBlank { card.title }
}
