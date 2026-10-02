package huilu.app

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import huilu.core.ActResult
import huilu.core.Clock
import huilu.core.Effect
import huilu.core.Engine
import huilu.core.Level
import huilu.core.Mode
import huilu.core.Timeline

/**
 * 进程内的单例：持有引擎，并把引擎要求的效果真正落到手机上，
 * 再把"现实中是否发生了"报回引擎。
 */
class App : Application() {
    lateinit var prefs: Prefs
    lateinit var log: FileLog
    lateinit var platform: AndroidPlatform
    lateinit var engine: Engine
    lateinit var notifier: Notifier
    val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    /** CheckInActivity 每次真正显示时记录，用于确认"弹出检查页"是否真的发生。 */
    @Volatile var shownCheckIn: Pair<String, Long>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        log = FileLog(filesDir)
        platform = AndroidPlatform(this)
        engine = Engine(log, object : Clock { override fun now() = App.now() }, platform, { prefs.settings() })
        notifier = Notifier(this)
        sync()
    }

    // ------------------------------------------------------------ 与引擎交互的唯一入口

    fun tick() = perform(engine.tick())

    fun perform(effects: List<Effect>) {
        for (e in effects) when (e) {
            is Effect.Dismiss -> {
                notifier.cancelCheckIn(e.checkInId)
                CheckInActivity.current?.onClosedElsewhere(e.checkInId)
            }
            is Effect.Intervene -> actuate(e)
        }
        sync()
    }

    private fun actuate(e: Effect.Intervene) {
        val i = e.intervention
        val c = e.checkIn
        try {
            when (i.level) {
                Level.NOTIFY -> {
                    if (c != null) notifier.showCheckIn(c, fullScreen = false)
                    report(i.id, notificationResult())
                }
                Level.INTERRUPT -> {
                    if (c != null) notifier.showCheckIn(c, fullScreen = true)
                    try {
                        startActivity(CheckInActivity.intent(this, c?.id))
                    } catch (ex: Exception) {
                        report(i.id, ActResult.FAILED to "启动检查页失败：${ex.javaClass.simpleName}")
                        return
                    }
                    main.postDelayed({
                        val shown = shownCheckIn
                        val ok = shown != null && shown.first == c?.id && now() - shown.second < 5_000
                        report(i.id, if (ok) ActResult.VERIFIED to "检查页已显示在前台"
                            else ActResult.NO_EFFECT to "系统没有让检查页出现在前台（可能缺少悬浮窗权限或被厂商拦截），只发出了通知")
                    }, 2_000)
                }
                Level.HOME -> {
                    val g = GuardService.instance
                    if (g == null) { report(i.id, ActResult.UNAVAILABLE to "无障碍服务未开启"); return }
                    val sent = g.goHome()
                    if (!sent) { report(i.id, ActResult.FAILED to "系统拒绝了返回桌面"); return }
                    main.postDelayed({
                        val fg = platform.foreground(now())
                        report(i.id, if (fg.pkg != i.target) ActResult.VERIFIED to "已离开「${platform.label(i.target ?: "")}」"
                            else ActResult.NO_EFFECT to "返回桌面后「${platform.label(i.target ?: "")}」仍在前台")
                    }, 1_500)
                }
                Level.BLOCK, Level.DEVICE -> report(i.id, ActResult.UNAVAILABLE to "这一级由引擎通过反复送回桌面实现，没有单独的设备动作")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "actuate", ex)
            report(i.id, ActResult.FAILED to (ex.message ?: ex.javaClass.simpleName))
        }
    }

    private fun notificationResult(): Pair<ActResult, String> {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return ActResult.FAILED to "通知被关闭，用户看不到这次检查"
        val ch = nm.getNotificationChannel(Notifier.CH_CHECK)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return ActResult.FAILED to "「检查」通知渠道被关闭"
        return ActResult.VERIFIED to "通知已发出（无法确认是否被看到）"
    }

    private fun report(id: String, r: Pair<ActResult, String>) {
        engine.interventionResult(id, r.first, r.second)
        notifyListeners()
    }

    // ------------------------------------------------------------ 让手机上的状态跟上引擎

    fun sync() {
        val s = engine.situation
        val active = s.mode != Mode.IDLE || s.probes.isNotEmpty()
        if (active) LoopService.start(this) else LoopService.stop(this)
        scheduleAlarm(engine.nextWakeAt())
        LoopService.instance?.refresh()
        if (s.pending == null) notifier.cancelAllCheckIns()
        notifyListeners()
    }

    private fun scheduleAlarm(at: Long?) {
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(this, 1, Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (at == null) { am.cancel(pi); return }
        val t = maxOf(at, now() + 5_000)
        if (canExactAlarm()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }

    fun canExactAlarm(): Boolean =
        Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun episodes() = Timeline.build(log.readAll())

    companion object {
        const val TAG = "huilu"
        lateinit var instance: App

        /** App 内唯一的时间来源。测试里替换成受控时钟。 */
        @Volatile var clock: () -> Long = { System.currentTimeMillis() }
        fun now(): Long = clock()
        fun of(ctx: Context) = ctx.applicationContext as App
    }
}
