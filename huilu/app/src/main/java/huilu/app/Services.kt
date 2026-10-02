package huilu.app

import android.accessibilityservice.AccessibilityService
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import huilu.core.AnswerKind
import huilu.core.Outcome
import huilu.core.Via

/**
 * 行动进行中保持运行的前台服务：
 * 1. 常驻通知把当前意图留在眼前（外置工作记忆）；
 * 2. 每 15 秒让引擎看一眼现实，发现偏离就主动重新进入。
 * 进程被杀时，AlarmManager 的兜底闹钟会在下一个关键时间点把它拉起来。
 */
class LoopService : Service() {
    private val loop = object : Runnable {
        override fun run() {
            try { App.of(this@LoopService).tick() } catch (e: Exception) { Log.w(App.TAG, "tick", e) }
            App.of(this@LoopService).main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val n = App.of(this).notifier.ongoing(App.of(this).engine.situation)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ID_ONGOING, n)
        App.of(this).main.postDelayed(loop, 1_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    fun refresh() {
        val app = App.of(this)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifier.ID_ONGOING, app.notifier.ongoing(app.engine.situation))
    }

    override fun onDestroy() {
        App.of(this).main.removeCallbacks(loop)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TICK_MS = 15_000L
        @Volatile var instance: LoopService? = null

        fun start(ctx: Context) {
            if (instance != null) return
            try {
                ctx.startForegroundService(Intent(ctx, LoopService::class.java))
            } catch (e: Exception) {
                // Android 12+ 不允许从后台随意启动前台服务；兜底闹钟会在允许的时机再试
                Log.w(App.TAG, "cannot start LoopService now", e)
            }
        }

        fun stop(ctx: Context) {
            if (instance != null) ctx.stopService(Intent(ctx, LoopService::class.java))
        }
    }
}

/** 兜底闹钟：即使服务被杀，到了下一个关键时间点也会醒来推进一次。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = App.of(ctx).tick()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = App.of(ctx).tick()
}

/** 通知上的按钮和"说一句"回复。 */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.of(ctx)
        val id = intent.getStringExtra(KEY_ID)
        when (intent.action) {
            CHECK_NOW -> app.perform(app.engine.checkNow())
            DONE -> app.perform(app.engine.stop(Outcome.DONE))
            ANSWER -> {
                val kind = AnswerKind.values().firstOrNull { it.name == intent.getStringExtra(KEY_KIND) } ?: return
                if (id == null) return
                app.perform(app.engine.answer(id, kind, via = Via.NOTIFICATION))
                app.engine.situation.lastClosed?.decision?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            }
            TEXT -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
                if (id == null || text.isNullOrEmpty()) return
                app.perform(app.engine.note(id, text, Via.NOTIFICATION))
                val c = app.engine.situation.pending ?: return
                if (app.prefs.llmReady) {
                    app.notifier.showCheckIn(c, fullScreen = false, extra = "已收到「$text」，正在判断…")
                    val pending = goAsync()
                    Judge.interpret(app, c, text) { pending.finish() }
                } else {
                    app.notifier.showCheckIn(c, fullScreen = false, extra = "已记下「$text」。再点一个选项，系统才知道下一步怎么做。")
                }
            }
        }
    }

    companion object {
        const val CHECK_NOW = "huilu.CHECK_NOW"
        const val DONE = "huilu.DONE"
        const val ANSWER = "huilu.ANSWER"
        const val TEXT = "huilu.TEXT"
        const val KEY_ID = "id"
        const val KEY_KIND = "kind"
        const val KEY_TEXT = "text"
    }
}

/**
 * 无障碍服务。只在两件事上用到：
 * - 执行"送回桌面"（GLOBAL_ACTION_HOME），这是不需要 root / Shizuku 就能可靠做到的最强动作；
 * - 前台窗口切换时立刻让引擎看一眼，屏蔽模式因此能在一两秒内生效。
 * 不读取任何窗口内容。
 */
class GuardService : AccessibilityService() {
    private var lastPkg: String? = null
    private val tick = Runnable { App.of(this).tick() }

    override fun onServiceConnected() {
        instance = this
        App.of(this).sync()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == lastPkg) return
        lastPkg = pkg
        val app = App.of(this)
        if (app.engine.situation.action == null) return
        // UsageStats 的事件比无障碍事件稍晚落盘，稍等再推进
        app.main.removeCallbacks(tick)
        app.main.postDelayed(tick, 800)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GuardService? = null
    }
}
