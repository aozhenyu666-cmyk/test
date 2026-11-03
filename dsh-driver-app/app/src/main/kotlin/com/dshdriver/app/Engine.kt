package com.dshdriver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.dshdriver.core.Card
import com.dshdriver.core.Directive
import com.dshdriver.core.Driver
import com.dshdriver.core.LiveInference
import com.dshdriver.core.Phase
import java.util.UUID

/**
 * APP 的中枢。驾驶员状态机只在专用线程（worker）上跑；界面、无障碍、音频回调都把事件投递进来。
 *
 * 0.2.0 起不再判"切走"、不再拉回：拦截由 WatchService 按用户勾选名单处理；学习会话只做不抢焦点的陪练小卡。
 */
object Engine {
    private const val TAG = "DriverEngine"
    private const val CHANNEL = "driver"
    private const val NOTE_END = 2

    lateinit var app: Context
        private set
    lateinit var store: Store
        private set

    private val worker = Handler(HandlerThread("driver").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())

    // ── 只在 worker 上读写 ──
    private var driver: Driver? = null
    private val live = LiveInference()

    // ── 主线程和 worker 都会读：volatile 快照 ──
    @Volatile var sessionId: String? = null
        private set
    @Volatile var snapshot: Driver.Snapshot? = null
        private set
    @Volatile var card: Card? = null
        private set
    @Volatile var anchorPkg: String? = null
        private set
    @Volatile var watchConnected = false

    /** 首页订阅：状态变了就刷新。在主线程回调。 */
    var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
        store = Store(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "锁定与结果", NotificationManager.IMPORTANCE_DEFAULT))
    }

    val locked: Boolean get() = snapshot?.phase.let { it != null && it != Phase.ENDED && it != Phase.IDLE }

    // ───────────────────────────── 会话 ─────────────────────────────

    fun start(topic: String, task: String, minutes: Int) = worker.post {
        if (driver?.phaseNow?.let { it != Phase.ENDED && it != Phase.IDLE } == true) return@post
        val settings = store.settings()
        val id = UUID.randomUUID().toString()
        val append = store.appender(id)
        val sink: (Map<String, Any?>) -> Unit = { row -> runCatching { append(row) } }
        // 配对了 DSH Go 就请大肥鱼出题（拿不到时驾驶员自己用模板）
        val brain = store.pairing()?.let(DshLink::brain)
        val d = Driver(brain = brain, profile = store.profile(), history = store.history(), agenda = store.agenda(), sink = sink, pickInteraction = store::nextInteractionKind)
        driver = d
        sessionId = id
        val out = d.start(System.currentTimeMillis(), topic, task, minutes, settings.anchors)
        main.post { LockService.start(app, task.ifBlank { topic }) }
        handle(out)
        worker.postDelayed(ticker, 1_000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val d = driver ?: return
            if (d.phaseNow == Phase.ENDED) return
            val now = System.currentTimeMillis()
            for (c in live.poll(now)) applyLive(d, c, now)
            handle(d.tick(now))
            if (d.phaseNow != Phase.ENDED) worker.postDelayed(this, 1_000)
        }
    }

    // ───────────────────────────── 输入 ─────────────────────────────

    /** 无障碍服务复核过的前台（主线程）。 */
    /** 最近读到的前台应用（只用来诊断：误判时一眼能看到它到底读到了什么）。 */
    data class Seen(val at: Long, val pkg: String, val className: String?)

    private val seen = ArrayDeque<Seen>()

    fun recentForeground(): List<Seen> = synchronized(seen) { seen.toList().asReversed() }

    /**
     * 无障碍服务复核过的前台（主线程）。
     *
     * 0.2.0 起**不再据此拦截或拉回**：在手机上做"只许用 Gemini"的允许名单，要预先知道 Gemini 会调起的
     * 所有系统界面（文件/相册选择器、相机、权限框……），每台手机都不同，误伤 Gemini 本身和传图片。
     * WatchService 按拦截名单处理（只拦指定 App），可选保留 Curbox 验证入口（[CheckActivity]）。
     * 这里只留诊断记录。
     */
    fun onForeground(context: Context, pkg: String?, className: String?) {
        if (pkg.isNullOrBlank()) return
        synchronized(seen) {
            if (seen.lastOrNull()?.pkg == pkg && seen.lastOrNull()?.className == className) return
            seen.addLast(Seen(System.currentTimeMillis(), pkg, className))
            while (seen.size > 15) seen.removeFirst()
        }
    }

    /** 系统音频状态（主线程回调）：有没有在录、有没有助手类声音在播。 */
    fun onAudio(recording: Boolean, assistantPlaying: Boolean) = worker.post {
        val d = driver ?: return@post
        val now = System.currentTimeMillis()
        for (c in live.onAudio(recording, assistantPlaying, now)) applyLive(d, c, now)
    }

    fun selfSpeaking(on: Boolean) = worker.post { live.selfSpeaking = on }

    private fun applyLive(d: Driver, c: LiveInference.Change, now: Long) = handle(
        when (c) {
            // "Live 被关了"只靠麦克风占用推断，不可靠；不再据此判你逃走、弹拉回卡
            is LiveInference.Change.Live -> emptyList()
            LiveInference.Change.AssistantStartedSpeaking -> d.audio(now, true)
            LiveInference.Change.AssistantStoppedSpeaking -> d.audio(now, false)
        },
    )

    fun surface(cardId: String, status: String, via: String) = worker.post { driver?.let { handle(it.surface(System.currentTimeMillis(), cardId, status, via)) } }
    fun reply(cardId: String, text: String?, choice: String?, channel: String) = worker.post { driver?.let { handle(it.reply(System.currentTimeMillis(), cardId, text, choice, channel)) } }
    fun pause(minutes: Int) = worker.post { driver?.let { handle(it.pause(System.currentTimeMillis(), minutes, "用户声明休息")) } }
    fun stop(reason: String) = worker.post { driver?.let { handle(it.stop(System.currentTimeMillis(), reason)) } }

    // ───────────────────────────── 输出 ─────────────────────────────

    /** worker 上：刷新快照、把指令交给主线程显示、收尾。 */
    private fun handle(out: List<Directive>) {
        val d = driver ?: return
        val snap = d.state()
        snapshot = snap
        anchorPkg = d.anchorPkg
        val current = d.currentCard
        val previous = card
        card = current
        main.post {
            for (x in out) Presenter.show(app, x)
            // 当前卡已经被别的途径结掉（比如 Gemini 开口推断已念）：撤掉还挂着的卡
            if (current != null && current.answered) Presenter.resolved(current.id)
            else if (previous != null && previous.id != current?.id && out.none { it.card?.id == current?.id }) Presenter.resolved(previous.id)
            onChange?.invoke()
        }
        out.firstOrNull { it.type == Directive.END }?.let { finish(d, it) }
    }

    private fun finish(d: Driver, end: Directive) {
        val snap = d.state()
        runCatching {
            store.saveHistory(d.history)
            d.agenda?.let { store.saveAgenda(it) }
            store.saveLast(Store.Last(end.at, end.reason ?: "", end.conclusion, end.summary ?: snap.summary, snap.topic, snap.task))
        }.onFailure { Log.w(TAG, "saving session failed", it) }
        // 配对了就把这次的结果交给大肥鱼存档（失败不影响什么）
        val pairing = store.pairing()
        val last = store.last()
        val agenda = d.agenda
        if (pairing != null && last != null && agenda != null) Thread { runCatching { DshLink.report(pairing, last, agenda) } }.start()
        worker.removeCallbacks(ticker)
        main.post {
            LockService.stop(app)
            notifyEnd(end)
            onChange?.invoke()
        }
    }

    private fun notifyEnd(end: Directive) {
        val s = end.summary ?: return
        val title = when {
            end.reason == "completed" -> "锁定完成"
            end.reason?.startsWith("stopped") == true -> "锁定已结束（主动结束）"
            else -> "锁定结束（没有给出结论）"
        }
        val pi = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_driver).setContentTitle(title)
            .setContentText("有依据的思考 ${s.grounded} 次｜回应 ${s.responded} 次｜切屏 ${s.interruptions} 次｜接回 ${s.resumed} 次")
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(NOTE_END, n) }
    }

    /** 回到 Live：打开锚点应用。无障碍服务被系统绑定着，系统允许从后台启动。 */
    fun relaunchAnchor(context: Context): Boolean {
        // 回到你设置里排第一个、而且装了的那个（默认 Gemini），不跟着"最近读到的"走
        val pkg = (listOfNotNull(anchorPkg) + store.settings().anchors).firstOrNull { isInstalled(context, it) } ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "could not relaunch $pkg", it) }.isSuccess
    }

    fun isInstalled(context: Context, pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null
}
