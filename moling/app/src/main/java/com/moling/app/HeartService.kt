package com.moling.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.moling.core.BrainFacts
import com.moling.core.flow.Flow
import com.moling.app.live.PetOverlay
import com.moling.app.overlay.Presence
import com.moling.core.mind.Actor
import com.moling.core.mind.Card
import com.moling.core.mind.Sense
import com.moling.core.mind.SenseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 后台心跳（M9）：让墨灵离开 App 也在。
 *
 * 前台服务 + 一条低优先级常驻通知；亮屏时每分钟 tick 一次，灭屏改成每 5 分钟一次（省电）。
 * 这一批只做心跳；M10 起 tick 会带上前台类别和总控健康，M12 起在心跳里做早晚自动。
 * 任何一次 tick 出错都只记日志，服务不能挂。
 */
class HeartService : Service() {
    companion object {
        private const val TAG = "HeartService"
        const val ACTION_STOP = "com.moling.app.heart.STOP"

        @Volatile
        var running = false
            private set

        /** 起服务；没通知权限、被系统限制都只记日志，不影响 App。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, HeartService::class.java))
            }.onFailure { Log.w(TAG, "后台心跳起不来", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HeartService::class.java)) }
        }

        /** 服务在不在跑（MainActivity 用它决定要不要自己再 tick）。 */
        fun isRunning(): Boolean = running
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** 上一次看到的前台（判断“换了 App”）和已经说过的那段连续娱乐。 */
    private var lastPkg = ""
    private var longKey = ""
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) BrainHost.onScreenOff(System.currentTimeMillis())
            if (intent.action == Intent.ACTION_USER_PRESENT) scope.launch {
                runCatching { com.moling.app.overlay.Presence.unlock(context) }
                    .onFailure { com.moling.app.overlay.Presence.error(context, "晨报失败：${it.javaClass.simpleName}") }
                runCatching { BrainHost.onUnlock(System.currentTimeMillis()) }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Notify.ensureChannels(this)
        ContextCompat.registerReceiver(this, unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 5 秒内必须进前台；通知内容用当前正在流的水道。
        goForeground()
        if (loop?.isActive != true) loop = scope.launch { heartbeat() }
        return START_STICKY
    }

    private fun goForeground() {
        val n = Notify.heart(this, heartText())
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notify.ID_HEART, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notify.ID_HEART, n)
            }
        }.onFailure { Log.w(TAG, "进前台失败", it) }
    }

    private suspend fun heartbeat() {
        while (true) {
            val now = System.currentTimeMillis()
            runCatching { BrainHost.tickNow(now) }.onFailure { Log.w(TAG, "心跳失败", it) }
            runCatching { feedSenses(now) }.onFailure { Log.w(TAG, "喂导演失败", it) }
            runCatching { capsule(now) }.onFailure { Log.w(TAG, "演员胶囊失败", it) }
            com.moling.app.overlay.Presence.tick(this, now)
            com.moling.app.widget.NowWidget.update(this)
            Notify.updateHeart(this, heartText())
            delay(if (interactive()) 60_000L else 300_000L)
        }
    }

    /**
     * Z8 和演员聊时的小胶囊：正在和 Gemini / ChatGPT 聊、而且前台就是它时，顶角挂一问 + 已聊几分钟；
     * 一旦离开演员就收起（收线仍然由 Presence 原来的流程管，这里不重复做）。
     */
    private fun capsule(now: Long) {
        runCatching { Presence.init(this) }
        val s = Presence.session.value
        val fg = BrainHost.foregroundNow(now)?.pkg.orEmpty()
        val actorPkg = s?.opening?.actor?.pkg.orEmpty()
        if (s != null && actorPkg.isNotBlank() && fg == actorPkg) {
            val line = s.opening.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(16).ifBlank { "聊完了就回来说一句" }
            PetOverlay.capsule(this, line, s.startedAt)
        } else {
            PetOverlay.hideCapsule(this)
        }
    }

    /**
     * 把“每分钟 / 前台换了 / 刷娱乐 15 分钟 / 打开演员”告诉导演（M15）。
     * 导演自己有限流，这里看到什么就说什么。
     */
    private suspend fun feedSenses(now: Long) {
        deliver(BrainHost.sense(Sense(SenseKind.TICK, now, "heart")))
        val fg = BrainHost.foregroundNow(now)
        val pkg = fg?.pkg.orEmpty()
        val category = BrainFacts.category(pkg, BrainHost.state.value.lastStatus)
        if (pkg.isNotBlank() && pkg != lastPkg) {
            deliver(
                BrainHost.sense(
                    Sense(SenseKind.FOREGROUND, now, "usage", mapOf("pkg" to pkg, "label" to (fg?.label ?: pkg), "category" to category)),
                ),
            )
            if (category == "ACTOR") {
                val actor = if (pkg == Actor.GEMINI_LIVE.pkg) "gemini" else "chatgpt"
                deliver(BrainHost.sense(Sense(SenseKind.ACTOR_OPEN, now, "usage", mapOf("actor" to actor))))
            }
        }
        val minutes = fg?.minutes ?: 0
        if (category == "ENTERTAINMENT" && minutes >= 15) {
            // 同一段连续娱乐只告诉一次：用“这段开始的时间”当 key。
            val key = pkg + ":" + (now - minutes * 60_000L) / 60_000L
            if (key != longKey) {
                longKey = key
                deliver(BrainHost.sense(Sense(SenseKind.LONG_ENTERTAINMENT, now, "usage", mapOf("pkg" to pkg, "minutes" to "\$minutes"))))
            }
        }
        lastPkg = pkg
    }

    /** 导演给的卡：App 在前台就交给今天页，不在前台发通知（M15）。 */
    private fun deliver(card: Card?) {
        val c = card ?: return
        if (com.moling.app.overlay.Presence.onCard(this, c)) return
        if (MainActivity.visible) BrainHost.setDirectorCard(c) else Notify.card(this, c.ask)
    }

    /** 亮屏着就勤一点，灭屏省电。 */
    private fun interactive(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    /** 常驻通知正文：今天水道里此刻该在的那条；没有就写今天还没排。 */
    private fun heartText(): String {
        val plan = BrainHost.state.value.flow ?: return "今天还没排水道"
        val minute = Flow.minuteOf(System.currentTimeMillis())
        return Flow.current(plan, minute)?.title?.ifBlank { "今天还没排水道" } ?: "今天还没排水道"
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(unlockReceiver) }
        com.moling.app.overlay.OverlayService.hide(this)
        scope.cancel()
        super.onDestroy()
    }
}