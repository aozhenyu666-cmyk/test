package com.zongkong.app.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.zongkong.app.ui.PermissionStatus
import com.zongkong.app.zk

/**
 * 外部唤醒：vivo/iQOO 会把常驻服务清掉，清掉之后就没人自愈了。
 *
 * 用一个 15 分钟的闹钟从外面把 [KeepAliveService] 拉起来——它一起来就会跑一遍
 * [SelfHeal.check]，该修的修、该提醒的提醒。没有 WorkManager 之类的强依赖也能做到这件事，
 * 代价是系统省电时闹钟会晚一点（允许，总比彻底不响好）。
 */
object HealAlarm {
    private const val TAG = "HealAlarm"
    private const val REQUEST = 9
    private const val ACTION = "com.zongkong.app.HEAL_TICK"
    private const val PREFS = "heal_alarm"

    /** 下一次该响的时刻。落盘是必须的：闹钟响过之后 PendingIntent 还在，
     *  光靠 FLAG_NO_CREATE 会以为“已经排着了”，于是永远不再排——唤醒链断在第一次。 */
    private const val K_AT = "next_at"

    /** 15 分钟：比常驻服务自己的 1 分钟粗得多，但足够把“被杀掉且没人拉起”这种状态在半天内纠正回来。 */
    const val EVERY_MS = 15 * 60_000L

    /**
     * 被叫醒之后要做什么。纯判断，方便单测（不碰 Android）。
     *
     * - 休假中：一下都别动。用户明确说了这段时间不管，闹钟还去拉服务就是打扰。
     * - 常驻服务还活着：它自己每分钟会看，闹钟只负责下次再排，不用再拉一遍（免得白闪通知）。
     * - 其余（被清掉了）：拉起来。
     */
    enum class Wake { NONE, START }

    fun decide(paused: Boolean, keepAliveAlive: Boolean, now: Long, pausedUntil: Long): Wake = when {
        paused && pausedUntil > now -> Wake.NONE
        keepAliveAlive -> Wake.NONE
        else -> Wake.START
    }

    /**
     * 现在该不该重新排一个闹钟。已经排着未来的一次就不排（重排等于把它往后推）。
     * 单独抽出来是因为踩过：响过之后 PendingIntent 还在，只看 FLAG_NO_CREATE 就再也排不上了。
     */
    fun shouldSchedule(nextAt: Long, now: Long): Boolean = nextAt <= now

    /**
     * 排下一次唤醒。
     *
     * 已经排着未来的一个就不重排：这个函数在常驻服务每次起来时都会调用，而重排会把它往后推 15 分钟——
     * 用户频繁开关应用、无障碍反复重连时，闹钟会被无限推后，等于没有。
     */
    fun schedule(context: Context) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val now = System.currentTimeMillis()
            if (!shouldSchedule(nextAt(context), now)) return
            setAt(context, now + EVERY_MS)
            val pi = pending(context)
            am.cancel(pi)
            // 准点闹钟要单独授权（权限页那一项）；没有就只能用“允许在打盹时响”的普通闹钟：
            // 系统会攒着一起发，晚个几分钟，但不会被直接吞掉。
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + EVERY_MS, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + EVERY_MS, pi)
        } catch (e: Exception) {
            Log.w(TAG, "排无障碍自愈闹钟失败", e)
        }
    }

    /** 强制重排（开机、应用更新后用：那时系统里的闹钟全没了，但落盘的时刻还留着）。 */
    fun reschedule(context: Context) {
        setAt(context, 0L)
        schedule(context)
    }

    /** 取消（诊断页清空、或者以后要关掉这层兜底时用）。 */
    fun cancel(context: Context) {
        try {
            setAt(context, 0L)
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        } catch (e: Exception) {
            Log.w(TAG, "取消无障碍自愈闹钟失败", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nextAt(context: Context): Long = runCatching { prefs(context).getLong(K_AT, 0L) }.getOrDefault(0L)

    private fun setAt(context: Context, at: Long) {
        runCatching { prefs(context).edit().putLong(K_AT, at).apply() }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST, Intent(context, HealReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * 闹钟到点：先把下一次排上（否则响一次就断了），再决定要不要拉常驻服务。
 * 只在这里做“叫醒”，不做修复判断——修复只有 [SelfHeal] 一处，免得两套逻辑打架。
 */
class HealReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 这一响已经用掉了：清掉落盘的时刻再排，才会真的排到下一个 15 分钟后
        HealAlarm.reschedule(context)
        val now = System.currentTimeMillis()
        val paused = runCatching { context.zk.store.config.value.pausedUntil }.getOrDefault(0L)
        if (HealAlarm.decide(paused > now, KeepAliveService.alive, now, paused) == HealAlarm.Wake.START) {
            Log.i("HealReceiver", "常驻服务不在，从闹钟拉起")
            KeepAliveService.start(context)
        }
        // 服务被拉起后要一分钟才看第一次；这里顺手把“小部件现在算不算在线”刷新一下，
        // 免得用户解锁看到的是十几分钟前的旧样子。
        if (runCatching { PermissionStatus.guardRunning(context) }.getOrDefault(false)) {
            runCatching { PetWidget.refresh(context) }
        }
    }
}
