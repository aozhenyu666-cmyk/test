package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.runtime.EngineExitLog
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeController
import java.io.File

/**
 * 2026-11-20 引擎保活：一条规矩——**只要不是他自己点了「停止」，引擎掉了就拉回来。**
 *
 * 之前的缺口（真机上「开了自启动，引擎还是会自己没了 / 失效」）：
 *  1. 开机 / 更新后只跑了时钟内核，没人拉引擎（要等他打开 App）；
 *  2. 引擎进了 ERROR（比如前台服务先被系统停了，再被杀 → restartSkipped），之后谁都不再拉；
 *  3. 整个主进程被厂商杀掉后，只剩 START_STICKY（vivo/iQOO 上经常不兑现）；无障碍还活着时
 *     它的 30 秒体检只在 INSTALLED 时拉，不管 ERROR；
 *  4. `:watchdog` 进程只看无障碍，不看引擎。
 *
 * 这里把这几处收成一个入口 [ensureIfWanted]：开机 / 闹钟醒 / 无障碍体检 / 看门狗 / 被杀没重启
 * 都走它。他点了「停止」就记下 wanted=false，谁都不拉，直到他再点「启动」。
 * ERROR 状态的拉起有上限（10 分钟一次、1 小时最多 3 次），免得真起不来时原地打转。
 * 后台拉不起前台服务（Android 12+ 的限制）就发一条通知，他点一下就起。
 */
object EngineKeeper {
    private const val TAG = "DshEngineKeeper"
    private const val FILE = "dafeiyu-engine-wanted"
    private const val FLUTTER_PREFS = "FlutterSharedPreferences"
    private const val AUTO_START = "flutter.auto_start_local"
    const val ACTION_KEEP = "io.github.ccch1mneyyy.dsh_app.ENGINE_KEEP"
    private const val NOTICE_CHANNEL = "dsh-engine-keeper"
    private const val NOTICE_ID = 3090

    private val main = Handler(Looper.getMainLooper())
    private val errorHeals = ArrayDeque<Long>()
    @Volatile private var lastHealAt = 0L
    @Volatile private var lastNoticeAt = 0L

    /** 跨进程读得到（看门狗在 `:watchdog`）：一个小文件，不走 SharedPreferences 缓存。缺省 = 想要（老版本的行为）。 */
    fun wanted(context: Context): Boolean = runCatching {
        val f = File(context.applicationContext.filesDir, FILE)
        !f.exists() || f.readText().trim() != "0"
    }.getOrDefault(true)

    fun markWanted(context: Context, on: Boolean, why: String) {
        val app = context.applicationContext
        runCatching {
            val f = File(app.filesDir, FILE)
            val prev = !f.exists() || f.readText().trim() != "0"
            f.writeText(if (on) "1" else "0")
            if (prev != on) EngineExitLog.note(app, if (on) "keeperWanted" else "keeperUserStopped", why)
        }
        if (on) cancelNotice(app)
    }

    /** 设置里「打开 App 时自动启动引擎」关掉了，也不在后台拉（他明确不要）。 */
    private fun autoStartOn(context: Context): Boolean = runCatching {
        val p = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        !p.contains(AUTO_START) || p.getBoolean(AUTO_START, true)
    }.getOrDefault(true)

    fun shouldKeep(context: Context): Boolean = wanted(context) && autoStartOn(context)

    /**
     * 主进程里调：引擎不在跑、他也没说停，就拉起来。返回做了什么（写进引擎退出日志，方便查）。
     */
    @Synchronized
    fun ensureIfWanted(context: Context, source: String): String {
        val app = context.applicationContext
        if (!shouldKeep(app)) return "not-wanted"
        val ctrl = runCatching { RuntimeController.get(app) }.getOrNull() ?: return "no-controller"
        if (ctrl.phase == RuntimeController.Phase.ERROR) {
            // 和打开 App 时的自动启动同一条规矩：失败卡在等他看（连挂两次且没开自动安全模式）就不拉。
            val st = runCatching { ctrl.status() }.getOrNull().orEmpty()
            val strikes = (st["strikes"] as? Number)?.toInt() ?: 0
            if (st["safeState"] == "off" && st["autoSafe"] == false && strikes >= 2) return "failure-card"
        }
        val now = SystemClock.elapsedRealtime()
        val decision = EngineKeepPolicy.decide(ctrl.phase.wire, now, lastHealAt, errorHeals.toList())
        if (decision != EngineKeepPolicy.Decision.START) return decision.wire
        lastHealAt = now
        if (ctrl.phase == RuntimeController.Phase.ERROR) {
            errorHeals.removeAll { now - it >= EngineKeepPolicy.ERROR_WINDOW_MS }
            errorHeals.addLast(now)
        }
        val res = runCatching { DshService.ensureEngine(app) }.getOrNull()
        EngineExitLog.note(app, "keeperHeal", "source=$source phase=${ctrl.phase.wire} result=${res?.status}")
        if (res?.needsForeground == true) postNotice(app)
        return res?.status ?: "failed"
    }

    /** 被杀了但前台服务已经不在（restartSkipped）：隔一会儿走一遍 [ensureIfWanted]。 */
    fun ensureLater(context: Context, source: String, delayMs: Long) {
        val app = context.applicationContext
        main.postDelayed({ runCatching { ensureIfWanted(app, source) } }, delayMs)
    }

    /** 别的进程（`:watchdog`）调：发一个显式广播进主进程，主进程死了也会被拉起来。 */
    fun pokeMain(context: Context, source: String) {
        if (!shouldKeep(context)) return
        runCatching {
            context.sendBroadcast(
                Intent(context, EngineKeeperReceiver::class.java).setAction(ACTION_KEEP).putExtra("source", source),
            )
        }.onFailure { Log.w(TAG, "poke failed", it) }
    }

    private fun postNotice(app: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNoticeAt < 30 * 60_000L && lastNoticeAt != 0L) return
        lastNoticeAt = now
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(NOTICE_CHANNEL, "引擎保活", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val intent = Intent(app, DshService::class.java).setAction(DshService.ACTION_ENSURE_ENGINE)
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(app, NOTICE_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            } else {
                PendingIntent.getService(app, NOTICE_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(app, NOTICE_CHANNEL)
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(app)
            }
            nm.notify(
                NOTICE_ID,
                builder.setSmallIcon(R.drawable.ic_stat_dsh)
                    .setContentTitle("大肥鱼引擎被系统停了")
                    .setContentText("点一下拉回来（系统不让后台自己拉）。常出现就去「后台耗电 / 自启动」里放行。")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun cancelNotice(app: Context) {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
    }
}

/** 纯规则（JVM 单测）：什么状态下拉、拉多勤。 */
object EngineKeepPolicy {
    const val MIN_GAP_MS = 60_000L
    const val ERROR_GAP_MS = 10 * 60_000L
    const val ERROR_WINDOW_MS = 60 * 60_000L
    const val ERROR_MAX_PER_WINDOW = 3

    enum class Decision(val wire: String) {
        START("start"), BUSY("busy"), NOT_INSTALLED("not-installed"), COOLDOWN("cooldown"), ERROR_CAP("error-cap")
    }

    fun decide(phase: String, now: Long, lastHealAt: Long, errorHeals: List<Long>): Decision = when (phase) {
        "running", "starting", "installing", "stopping" -> Decision.BUSY
        "notInstalled" -> Decision.NOT_INSTALLED
        "error" -> {
            val recent = errorHeals.filter { now - it in 0 until ERROR_WINDOW_MS }
            when {
                recent.size >= ERROR_MAX_PER_WINDOW -> Decision.ERROR_CAP
                recent.isNotEmpty() && now - recent.max() < ERROR_GAP_MS -> Decision.COOLDOWN
                lastHealAt != 0L && now - lastHealAt < MIN_GAP_MS -> Decision.COOLDOWN
                else -> Decision.START
            }
        }
        else -> if (lastHealAt != 0L && now - lastHealAt < MIN_GAP_MS) Decision.COOLDOWN else Decision.START
    }
}

/** 主进程里的入口：看门狗（`:watchdog`）发来的「看看引擎还在不在」。不导出。 */
class EngineKeeperReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != EngineKeeper.ACTION_KEEP) return
        runCatching { EngineKeeper.ensureIfWanted(context, intent.getStringExtra("source") ?: "watchdog") }
    }
}
