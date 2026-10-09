package com.zongkong.app.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.zongkong.app.system.Notifications
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.DayOps
import com.zongkong.core.Engine
import com.zongkong.core.GatePhase
import com.zongkong.core.Reason
import com.zongkong.core.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 总控的眼睛：无障碍服务。
 *
 * 只订阅 TYPE_WINDOW_STATE_CHANGED，只读事件里的包名（canRetrieveWindowContent = false）。
 * 做四件事：严管时把拦截名单里的应用弹回总控；累计娱乐应用时长；到点发提醒；每 15 分钟同步一次 Notion。
 */
class GuardService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = zk.store

    /** 当前前台应用和它切到前台的时刻。 */
    private var fgPkg: String? = null
    private var fgSince = 0L
    private var lastBounce = 0L
    private var lastStatusText = ""
    private var lastSync = 0L

    /** 输入法、系统界面：它们弹出来时不算切换应用。 */
    private var overlays: Set<String> = emptySet()
    private var overlaysAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        val now = System.currentTimeMillis()
        val hb = store.heartbeat
        if (hb > 0 && now - hb > OFFLINE_THRESHOLD) {
            // 上次心跳到现在，总控没有在运行
            store.updateToday(now) { DayOps.offline(it, hb, now) }
        }
        store.heartbeat = now
        lastStatusText = ""
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "tick 出错", e)
                }
                delay(TICK_MILLIS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in overlays()) return
        val now = System.currentTimeMillis()
        if (pkg != fgPkg) {
            accountPlay(now)
            fgPkg = pkg
            fgSince = now
        }
        enforce(pkg, now)
    }

    private fun overlays(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - overlaysAt < 10 * 60_000 && overlays.isNotEmpty()) return overlays
        overlays = buildSet {
            add("com.android.systemui")
            try {
                getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) }
            } catch (e: Exception) {
                Log.w(TAG, "读取输入法列表失败", e)
            }
        }
        overlaysAt = now
        return overlays
    }

    private fun interactive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive != false

    /** 把前台娱乐应用这段时间记进今天的娱乐时长。 */
    private fun accountPlay(now: Long) {
        val p = fgPkg
        if (p != null && p in store.config.value.blocked && interactive()) {
            val sec = (now - fgSince) / 1000
            if (sec in 1..(2 * TICK_MILLIS / 1000 + 60)) store.updateToday(now) { DayOps.addPlay(it, p, sec) }
        }
        fgSince = now
    }

    private fun enforce(pkg: String, now: Long) {
        if (pkg == packageName) return
        if (pkg !in store.config.value.blocked) return
        if (!interactive()) return
        val status = store.status(now)
        if (!status.strict) return
        if (now - lastBounce < 1500) return
        lastBounce = now
        store.updateToday(now) { DayOps.bounce(it) }
        try {
            startActivity(BlockActivity.intent(this, pkg))
        } catch (e: Exception) {
            Log.e(TAG, "打不开拦截页", e)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        store.heartbeat = now
        if (interactive()) accountPlay(now) else fgSince = now
        val status = store.status(now)
        fgPkg?.let { if (status.strict) enforce(it, now) }
        alerts(status, now)
        val text = Engine.summary(status)
        val title = "总控 · ${status.headline}"
        if ("$title|$text" != lastStatusText) {
            lastStatusText = "$title|$text"
            Notifications.showStatus(this, title, text)
        }
        if (now - lastSync > SYNC_MILLIS) {
            lastSync = now
            scope.launch(Dispatchers.IO) {
                try {
                    zk.actions.sync()
                } catch (e: Exception) {
                    Log.w(TAG, "同步失败", e)
                }
            }
        }
    }

    /** 每条提醒每天只发一次。 */
    private fun alerts(status: Status, now: Long) {
        for (g in status.gates) {
            if (g.done) continue
            val id = g.gate.id
            val name = "${g.gate.dept.label}「${g.gate.title}」"
            when {
                g.phase == GatePhase.OVERDUE && store.firstAlert("over:$id") ->
                    Notifications.alert(this, "$name 已超时", "现在起拦截名单里的应用都会被弹回。交了就放行。", "gate/$id")
                g.phase == GatePhase.DUE_SOON && store.firstAlert("soon:$id") ->
                    Notifications.alert(
                        this, "$name 还有 ${((g.deadlineAt - now) / 60_000).coerceAtLeast(1)} 分钟截止",
                        g.gate.instruction.take(60), "gate/$id",
                    )
                g.blocking && g.phase != GatePhase.OVERDUE && now - g.openAt < 3600_000 && store.firstAlert("open:$id") ->
                    Notifications.alert(this, "$name 开放了", "交了才放行娱乐应用，${DayClock.hhmm(g.gate.deadline)} 截止。", "gate/$id")
            }
        }
        status.silenceDueAt?.let { due ->
            val silent = status.reasons.any { it is Reason.Silence }
            if (silent && store.firstAlert("sil:$due")) {
                Notifications.alert(this, "该报到了", "已经 ${store.config.value.silenceHours} 小时没有汇报。报一句现在在做什么。", "report")
            } else if (!silent && due - now in 0..15 * 60_000L && store.firstAlert("silwarn:$due")) {
                Notifications.alert(this, "15 分钟内报个到", "太久不汇报会进入严管。", "report")
            }
        }
        if (status.quotaMin > 0) {
            if (status.reasons.any { it is Reason.Quota } && store.firstAlert("quota")) {
                Notifications.alert(this, "今天的娱乐额度用完了", "${status.playMin} 分钟。娱乐应用今天不再放行。", null)
            } else if (status.playMin * 10 >= status.quotaMin * 8 && store.firstAlert("quota80")) {
                Notifications.alert(this, "娱乐已用 ${status.playMin}/${status.quotaMin} 分钟", "快到上限了。", null)
            }
        }
        store.today.value.emergencies.lastOrNull()?.let { e ->
            if (now >= e.until && now - e.until < 5 * 60_000 && store.firstAlert("emg:${e.until}")) {
                Notifications.alert(this, "紧急放行结束", "恢复检查。", null)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        offline()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        offline()
        scope.cancel()
        super.onDestroy()
    }

    private fun offline() {
        if (!running) return
        running = false
        accountPlay(System.currentTimeMillis())
        Notifications.showStatus(this, "总控已离线", "无障碍服务被关闭了。离线的时间会记成“失联”，写进日终验收。")
    }

    companion object {
        private const val TAG = "GuardService"
        private const val TICK_MILLIS = 20_000L
        private const val SYNC_MILLIS = 15 * 60_000L
        private const val OFFLINE_THRESHOLD = 5 * 60_000L

        @Volatile
        var running = false
            private set
    }
}
