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

    /** 当前前台应用和它切到前台的时刻（fgSince 每次记娱乐时长后重置；fgStartedAt 是这一段真正开始的时刻）。 */
    @Volatile private var fgPkg: String? = null
    @Volatile private var fgSince = 0L
    @Volatile private var fgStartedAt = 0L
    private var lastShot = 0L
    /** 截图机会：按“每小时几张”一直攒（锁屏、熄屏时也攒），亮屏能截时再用掉。 */
    private var shotDebt = 0.0
    private var nextShotAt = 0L
    private var lastWatch = 0L
    @Volatile private var bgBusy = false
    /** 最近一次解锁的时刻（战友模式解锁问候用）。 */
    @Volatile var unlockedAt = 0L

    /** 亮屏熄屏：记进时间线（总控对账用）。 */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            try {
                val now = System.currentTimeMillis()
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        accountPlay(now)
                        store.logSwitch(now, com.zongkong.core.boss.Timeline.OFF)
                    }
                    Intent.ACTION_SCREEN_ON -> store.logSwitch(now, fgPkg ?: com.zongkong.core.boss.Timeline.ON)
                    Intent.ACTION_USER_PRESENT -> {
                        // 解锁了：锁屏时攒下的截图机会，过一小会儿补上；战友模式下打个招呼
                        nextShotAt = now + kotlin.random.Random.nextLong(20_000, 180_000)
                        unlockedAt = now
                    }
                }
                fgStartedAt = now
            } catch (e: Throwable) {
                Log.e(TAG, "亮屏广播处理出错", e)
            }
        }
    }
    private var lastBounce = 0L
    private var lastStatusText = ""
    private var lastSync = 0L

    /** 输入法、系统界面：它们弹出来时不算切换应用。 */
    private var overlays: Set<String> = emptySet()
    private var overlaysAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            connected()
        } catch (e: Throwable) {
            // 这里抛出去，系统会把服务标成“出现故障”，只能手动关开一次：一律接住
            Log.e(TAG, "连接时出错", e)
        }
    }

    private fun connected() {
        running = true
        runCatching { Diag.log(this, "connected", "无障碍服务连上；SDK=${android.os.Build.VERSION.SDK_INT}") }
        val now = System.currentTimeMillis()
        val hb = store.heartbeat
        if (hb > 0 && now - hb > OFFLINE_THRESHOLD) {
            // 上次心跳到现在，总控没有在运行
            store.updateToday(now) { DayOps.offline(it, hb, now) }
        }
        // 时间线：上次心跳之后到现在总控没在看，记成“没有记录”
        if (hb > 0 && now - hb > 2 * TICK_MILLIS) store.logSwitch(hb, com.zongkong.core.boss.Timeline.GAP)
        store.logSwitch(now, if (interactive()) com.zongkong.core.boss.Timeline.ON else com.zongkong.core.boss.Timeline.OFF)
        try {
            registerReceiver(screenReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        } catch (e: Exception) {
            Log.w(TAG, "注册亮屏广播失败", e)
        }
        store.heartbeat = now
        lastStatusText = ""
        com.zongkong.app.system.Pet.attach(this)
        com.zongkong.app.system.KeepAliveService.start(this)
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Throwable) {
                    Log.e(TAG, "tick 出错", e)
                }
                // 重活（提醒、总控者的日程、抽查、和电脑同步）放到后台线程：主线程卡住会被系统判成“无响应”停掉服务
                if (!bgBusy) {
                    bgBusy = true
                    launch(Dispatchers.Default) {
                        try {
                            background(System.currentTimeMillis())
                        } catch (e: Throwable) {
                            Log.e(TAG, "后台检查出错", e)
                        } finally {
                            bgBusy = false
                        }
                    }
                }
                delay(TICK_MILLIS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            onWindow(event)
        } catch (e: Throwable) {
            Log.e(TAG, "处理窗口切换出错", e)
            runCatching { Diag.log(this, "event_error", "处理窗口: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private var lastSentinelWrite = 0L

    /** 给哨兵写心跳和现在的拦截状态（最多 5 秒一次）。哨兵看到心跳停了就接管。 */
    private fun beatSentinel(now: Long, status: () -> com.zongkong.core.Status) {
        if (now - lastSentinelWrite < 5_000) return
        lastSentinelWrite = now
        val c = store.config.value
        val st = status()
        SentinelLink.writeState(this, c.blocked, st.strict, maxOf(c.pausedUntil, st.emergencyUntil ?: 0L), now)
    }

    private fun onWindow(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        System.currentTimeMillis().let { t -> beatSentinel(t) { store.status(t) } }
        Log.d(TAG, "窗口切换：$pkg / ${event.className}")
        if (pkg in overlays()) return
        val now = System.currentTimeMillis()
        if (pkg != fgPkg) {
            accountPlay(now)
            // 做一步行动时离开了总控：记下离开时刻，回来时提示记断点
            if (fgPkg == packageName && pkg != packageName && store.work.value.session != null) zk.actions.work.left()
            fgPkg = pkg
            fgSince = now
            fgStartedAt = now
            store.logSwitch(now, pkg)
        }
        if (lockdown(pkg, now)) return
        enforce(pkg, now)
        // 低屏模式：连续用太久的应用弹回去做题（不拦全封，只限时 + 冷却 + 做题）
        runCatching { Watch.tick(this, pkg, now) }
    }

    /**
     * 总控时间：白名单以外的应用（包括桌面）一出现就回到总控时间。
     * 失联三天：下次打开白名单以外的应用，直接进入总控时间。
     * 处理了返回 true。
     */
    private fun lockdown(pkg: String, now: Long): Boolean {
        if (pkg == packageName || !interactive()) return false
        val cfg = store.config.value.hq
        if (store.config.value.pausedUntil > now) return false
        var session = store.hq.value.active
        if (session == null && store.absence(now) == com.zongkong.core.hq.Hq.Absence.FORCE && !com.zongkong.core.hq.Hq.allows(pkg, packageName, cfg, overlays())) {
            Log.i(TAG, "失联太久，打开 $pkg 时进入总控时间")
            session = zk.actions.hq.start(com.zongkong.core.hq.HqTrigger.ESCALATION)
        }
        if (session == null || now < session.suspendedUntil) return false
        if (session.trigger == com.zongkong.core.hq.HqTrigger.UNANSWERED && now >= session.endsAt) return false
        if (com.zongkong.core.hq.Hq.allows(pkg, packageName, cfg, overlays())) return false
        if (now - lastBounce < 1500) return true
        lastBounce = now
        Log.i(TAG, "总控时间，弹回 $pkg")
        store.updateToday(now) { DayOps.bounce(it) }
        runCatching { Diag.log(this, "lockdown", "总控时间弹回 $pkg") }
        try {
            startActivity(
                Intent(this, com.zongkong.app.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "hq"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "打不开总控时间", e)
        }
        return true
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
        if (!interactive()) {
            Log.i(TAG, "拦截名单应用 $pkg 在前台，但屏幕没亮，不处理")
            return
        }
        val status = store.status(now)
        if (!status.strict) {
            Log.i(TAG, "放行 $pkg：${status.headline} ${status.reasons.map { it.text }} 紧急=${status.emergencyUntil} 休假=${status.paused}")
            return
        }
        Log.i(TAG, "严管，弹回 $pkg：${status.reasons.firstOrNull()?.text}")
        if (now - lastBounce < 1500) return
        lastBounce = now
        store.updateToday(now) { DayOps.bounce(it) }
        runCatching { Diag.log(this, "bounce", "严管弹回 $pkg：${status.reasons.firstOrNull()?.text.orEmpty()}") }
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
        beatSentinel(now) { status }
        fgPkg?.let { if (status.strict) enforce(it, now) }
        // 低屏模式：没有窗口切换事件时也按秒盯着（比如一直停在一个应用里）
        fgPkg?.let { p -> runCatching { Watch.tick(this, p, now) } }
        alerts(status, now)
        // 总控时间刚开始时你正停在别的应用里：也拉回来
        fgPkg?.let { if (store.hq.value.active != null) lockdown(it, now) }
        com.zongkong.app.system.Pet.refresh(this)
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
                try {
                    zk.actions.absorbYishou()
                } catch (e: Exception) {
                    Log.w(TAG, "读一手总结失败", e)
                }
                // 新装的娱乐应用自动进拦截名单
                try {
                    AutoBlock.apply(this@GuardService)
                } catch (e: Exception) {
                    Log.w(TAG, "自动识别娱乐应用失败", e)
                }
                // 看证据的关卡（排了行动、一手练够了组数……）够了就自动打勾（早上的部署除外：要你自己确认）
                try {
                    val passed = zk.actions.autoVerify()
                    if (passed.isNotEmpty()) Notifications.alert(this@GuardService, "自动验收通过", passed.joinToString("、"), "rhythm")
                } catch (e: Exception) {
                    Log.w(TAG, "自动验收失败", e)
                }
            }
        }
    }

    /** 后台线程上的检查：到点提醒、总控者的日程、震动、眼睛。 */
    private fun background(now: Long) {
        com.zongkong.app.system.Reminders.check(this)
        com.zongkong.app.system.Buzz.tick(this)
        watch(now)
        com.zongkong.app.system.Partner.afterUnlock(this, unlockedAt, now)
    }

    /**
     * 总控者的眼睛：连续娱乐太久就找你；开了截图抽查就不定时截一张。
     */
    private fun watch(now: Long) {
        shots(now)
        val boss = store.config.value.boss
        val pkg = fgPkg ?: return
        if (!interactive() || pkg == packageName || pkg in overlays()) return
        if (store.firstAlert("prune")) store.prune(now)
        // 连续娱乐
        val minutes = (now - fgStartedAt) / 60_000
        val binge = com.zongkong.core.boss.Intensity.bingeMinutes(boss, store.stance(now))
        if (binge > 0 && pkg in store.config.value.blocked && fgStartedAt > 0 && minutes >= binge &&
            store.firstAlert("binge:$fgStartedAt")
        ) {
            snap(pkg, now, force = true)
            val name = com.zongkong.app.data.AppNames.label(this, pkg)
            zk.chat.proactive(
                "binge",
                "[触发·连续娱乐] 用户已经连续用「$name」$minutes 分钟（${com.zongkong.core.boss.Timeline.hm(fgStartedAt, store.zone)} 开始）。" +
                    "先看 device_timeline（最近 2 小时）和档案里今天的主线、约定，再找用户：说出你看到的数字，问一个问题，" +
                    "最后给【选项】现在去做主线｜再 10 分钟就停｜进总控时间。用户选“再 10 分钟”就用 commit 记成约定。",
                fallback = "「$name」已经连续 $minutes 分钟了。现在停下，回到主线。",
            )
            com.zongkong.app.system.Buzz.once(this, com.zongkong.app.system.Buzz.Level.FIRM)
        }
    }

    /** 截图抽查：机会按时间一直攒（熄屏、锁屏时也攒），亮屏能截时用掉；间隔随机，猜不到。 */
    private fun shots(now: Long) {
        val boss = store.config.value.boss
        val dt = if (lastWatch == 0L) 0L else (now - lastWatch).coerceIn(0, 3600_000L)
        lastWatch = now
        val rate = com.zongkong.core.boss.Intensity.shotsPerHour(boss, store.stance(now))
        if (!boss.shots || rate <= 0) { shotDebt = 0.0; return }
        shotDebt = (shotDebt + rate * dt / 3_600_000.0).coerceAtMost(rate * 2.0)
        val pkg = fgPkg ?: return
        if (!interactive() || pkg == packageName || pkg in overlays()) return
        if (shotDebt >= 1.0 && now >= nextShotAt) {
            val gap = (3_600_000L / rate).coerceAtLeast(3 * 60_000L)
            nextShotAt = now + kotlin.random.Random.nextLong(gap / 3, gap)
            snap(pkg, now, banked = true)
        }
    }

    /** 截一张屏（Android 11 起，无障碍服务可以截图）。只存在手机上；电话、总控自己不截。 */
    private fun snap(pkg: String, now: Long, force: Boolean = false, banked: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        if (!store.config.value.boss.shots) return
        if (pkg in com.zongkong.core.hq.ALWAYS_ALLOWED || pkg == packageName) return
        if (!force && now - lastShot < 3 * 60_000) return
        if (now - lastShot < 2_000) return
        lastShot = now
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        hb.close()
                    }
                    bmp ?: return
                    if (banked) shotDebt = (shotDebt - 1.0).coerceAtLeast(0.0)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val w = 720
                            val small = if (bmp.width > w) android.graphics.Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
                            java.io.File(store.shotsDir, "${now}_$pkg.jpg").outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "存截图失败", e)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "截图失败：$errorCode")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "截图失败", e)
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
                // 凌晨开放的关卡不半夜吵醒你：等到检查时段开始再提醒
                g.blocking && g.phase != GatePhase.OVERDUE && awake(status, now) && store.firstAlert("open:$id") ->
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

    private fun awake(status: Status, now: Long): Boolean {
        val c = store.config.value
        val date = java.time.LocalDate.parse(status.date)
        val start = DayClock.at(date, c.activeStart, store.zone)
        val end = DayClock.at(date, c.activeEnd, store.zone).let { if (it <= start) it + 24 * 3600_000L else it }
        return now in start until end
    }

    override fun onInterrupt() {
        runCatching { Diag.log(this, "event_error", "onInterrupt") }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { Diag.log(this, "unbound", "系统解绑无障碍服务（intent=${intent?.action ?: "-"}）") }
        offline()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { Diag.log(this, "destroyed", "无障碍服务被销毁") }
        com.zongkong.app.system.Pet.detach()
        offline()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun offline() {
        if (!running) return
        running = false
        runCatching { Diag.log(this, "offline", "无障碍服务离线（拦不住、看不到）") }
        // 告诉哨兵：总控这就下线了，马上接管（崩溃时走不到这里，哨兵等心跳停 90 秒）
        runCatching { SentinelLink.writeState(this, store.config.value.blocked, true, store.config.value.pausedUntil, at = 0L) }
        accountPlay(System.currentTimeMillis())
        store.logSwitch(System.currentTimeMillis(), com.zongkong.core.boss.Timeline.GAP)
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
