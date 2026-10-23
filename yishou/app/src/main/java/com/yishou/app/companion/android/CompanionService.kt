package com.yishou.app.companion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.companion.core.AskOutcome
import com.yishou.app.companion.core.Capsule
import com.yishou.app.companion.core.CapsuleState
import com.yishou.app.companion.core.CaptureHolder
import com.yishou.app.companion.core.Chunk
import com.yishou.app.companion.core.CoachInput
import com.yishou.app.companion.core.CompanionSession
import com.yishou.app.companion.core.Conductor
import com.yishou.app.companion.core.ContextTimeline
import com.yishou.app.companion.core.InterruptBudget
import com.yishou.app.companion.core.TranscriptAssembler
import com.yishou.app.companion.core.VideoCoach
import com.yishou.app.companion.core.VoiceAnswer
import com.yishou.app.speech.Heard
import com.yishou.app.llm.ChatClient
import com.yishou.app.log.RunLog
import com.yishou.app.look.ScreenLookService
import com.yishou.app.scroll.ScrollService
import com.yishou.app.window.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 视频陪练跑起来的那一场：投屏 + 内录 + 分块转写 + 时间轴 + 策略 + 朗读 + 记账。
 *
 * 资源只有一个主人：进场先跟看屏、刷着练、探针抢投屏账本，抢不到就退出去说清楚是谁占着。
 * 停止（用户点、系统收授权、到点）必须：取消在路上的请求、停朗读、放投屏和录音、关通知。
 */
class CompanionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as YishouApp

    private var projection: android.media.projection.MediaProjection? = null
    private var audio: PlaybackCaptureProbe? = null
    private var frames: ScreenFrameProbe? = null
    private var tap: TranscriptTap? = null
    private var speaker: Speaker? = null
    private var store: CompanionStore? = null
    private var media: MediaControlProbe? = null

    private var timeline: ContextTimeline? = null
    private var assembler: TranscriptAssembler? = null
    private var session: CompanionSession? = null
    private var conductor: Conductor? = null
    private var coach: VideoCoach? = null
    private var ports: AndroidCoachPorts? = null

    private var sessionId = 0L
    private var startedWall = 0L
    private var goal = ""
    private var pkg = ""
    private var ticker: Job? = null
    /** 正在听用户答这一问；同一时间只开一次麦 */
    private var listening: Job? = null
    private var lastTryAt = 0L
    private var nextObsId = 0L
    private var asked = 0
    private var missed = 0
    private var stopped = false
    /** 以前没到/差一点的动作，出题时带上去，好接着练 */
    private var memory: List<com.yishou.app.companion.core.AttemptMemory> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> answer(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            ACTION_READY -> ready()
            ACTION_SKIP -> skip()
            ACTION_VOICE_DONE -> app.voice.finish()
            ACTION_STOP -> stopAll()
            else -> if (!started(intent)) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun started(intent: Intent?): Boolean {
        if (session != null) return true
        ensureChannel(this)
        // 没有录音权限时不能带 microphone 类型起前台服务，系统会直接拒绝
        val micReady = app.voice.hasPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 后台开麦要 microphone 类型（Android 11 起），投屏要 mediaProjection；有麦克风就一起要
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (micReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(ID, notification("正在准备"), types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification("正在准备"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notification("正在准备"))
        }
        // 缺东西就别开：开一场一个字都不说的会话，比不开更糟
        val ready = com.yishou.app.companion.core.CompanionChecks.startup(
            llmReady = app.settings.llm.value.isComplete,
            sttReady = app.settings.speech.value.sttReady,
            canRecord = micReady,
            notifyAllowed = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled(),
        )
        if (!ready.canStart) return fail(ready.blockers.first())

        // 投屏只有一份：看屏、刷着练占着的时候不硬开
        if (ScreenLookService.running.value) return fail("看屏正开着，先到通知栏停掉看屏")
        if (ScrollService.running.value) return fail("刷着练正开着，先到通知栏停掉刷着练")
        CaptureArbiter.acquire(CaptureHolder.COMPANION)?.let { return fail(it) }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || code == 0) return fail("没有拿到投屏授权")

        goal = intent.getStringExtra(EXTRA_GOAL).orEmpty()
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 25)
        val gear = intent.getStringExtra(EXTRA_GEAR) ?: "ACCOMPANY"
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()

        val p = try {
            getSystemService(android.media.projection.MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            RunLog.e("视频陪练", "拿不到投屏授权", e)
            return fail("系统拒绝给投屏：${e.message ?: e.javaClass.simpleName}")
        }
        if (p == null) return fail("没有拿到投屏授权（系统回执无效，回执码 $code）")
        projection = p
        p.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                // 系统收回授权：立刻收摊，账也清掉
                publish("投屏被系统收回了，这一场结束", running = false)
                stopAll()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        startedWall = System.currentTimeMillis()
        // 场次 ID 就用这个时间：端口、会话、时间轴、数据库主键必须是同一个值
        sessionId = startedWall
        nextObsId = 0
        asked = 0
        missed = 0
        stopped = false

        val s = Speaker(this)
        speaker = s
        val st = CompanionStore(this, app.database)
        store = st
        val mediaProbe = MediaControlProbe(this)
        media = mediaProbe
        val tl = ContextTimeline(sessionId = startedWall)
        timeline = tl
        assembler = TranscriptAssembler(
            sessionId = startedWall,
            appPackage = pkg,
            epoch = tl.epoch(),
            nextId = { ++nextObsId },
        )

        val sess = CompanionSession(sessionId = startedWall, clock = { elapsed() }, answerWaitMs = 15_000)
        session = sess
        val ch = VideoCoach(InterruptBudget()) { elapsed() }
        coach = ch
        val pt = AndroidCoachPorts(
            app = app,
            chat = { ChatClient(config = { app.settings.llm.value }, http = ChatClient.defaultHttpClient(60), usage = app.stats) },
            speaker = s,
            media = mediaProbe,
            store = st,
            sessionId = startedWall,
            pkg = { pkg },
            epoch = { tl.epoch() },
            now = { System.currentTimeMillis() },
        )
        ports = pt
        conductor = Conductor(sess, ch, pt, { elapsed() })

        // 内录
        val a = PlaybackCaptureProbe(this)
        audio = a
        val audioErr = a.start(p, scope)
        if (audioErr != null) publish(audioErr)

        // 分块转写：填了听写接口才转，没填就只观察、不打字问
        if (app.settings.speech.value.sttReady) {
            tap = TranscriptTap(
                scope = scope,
                transcribe = { wav ->
                    when (val r = app.speechApi.transcribe(wav)) {
                        is com.yishou.app.llm.LlmResult.Ok -> r.value
                        is com.yishou.app.llm.LlmResult.Err -> null
                    }
                },
                onChunk = { start, end, text -> onTranscript(start, end, text) },
                onFailed = { start, end, why -> onTranscriptFailed(start, end, why) },
            ).also { t -> a.tap = { buf, n, at -> t.feed(buf, n, at) } }
        }

        frames = ScreenFrameProbe(this).also { it.start(p) }

        app.appScopeLaunch { st.openSession(startedWall, goal, minutes, gear, pkg) }

        ready.notes.forEach { RunLog.e("视频陪练", it) }
        if (ready.notes.isNotEmpty()) publish(ready.notes.joinToString("；"))

        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                step()
            }
        }
        publish("开局了：${Capsule.text(capsuleState())}", running = true)
        return true
    }

    private fun fail(reason: String): Boolean {
        CaptureArbiter.release(CaptureHolder.COMPANION)
        publish(reason, running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return false
    }

    // ---- 材料进来 ----

    private fun onTranscript(start: Long, end: Long, text: String) {
        val tl = timeline ?: return
        val a = assembler ?: return
        // 换段了就换一本拼装账，不然旧 epoch 的转写会被判成过期
        if (a.epoch != tl.epoch()) {
            val next = TranscriptAssembler(startedWall, pkg, tl.epoch(), { ++nextObsId })
            assembler = next
            next.accept(Chunk(start, end, text))?.let { tl.add(it) }
            return
        }
        a.accept(Chunk(start, end, text))?.let { tl.add(it) }
    }

    private fun onTranscriptFailed(start: Long, end: Long, why: String) {
        assembler?.fail(start, end)
        RunLog.e("视频陪练", "这一块没转成：$why")
    }

    // ---- 每秒走一次 ----

    private fun step() {
        val sess = session ?: return
        val co = conductor ?: return
        // 先认目标、认跳播：认出换了一段，旧问题就不能再出声
        watchSource()
        if (co.tick()) {
            missed++
            stopListening()
            publish("这一问没接上，记一笔（不算答错）")
        }
        publish(Capsule.text(capsuleState()), running = true)

        // 只有在听着、且隔了几秒才试一次，别每秒都叫模型
        if (sess.phase() == com.yishou.app.companion.core.Phase.OBSERVE && elapsed() - lastTryAt >= 5_000) {
            lastTryAt = elapsed()
            val tl = timeline ?: return
            val slice = tl.slice(elapsed(), System.currentTimeMillis() + 20_000)
            scope.launch {
                when (val out = co.tryAsk(CoachInput(elapsed(), goal, slice, memory, elapsed()))) {
                    is AskOutcome.Asked -> {
                        asked++
                        publish("", question = out.question)
                        // 念完才开始听：念的时候开麦会把朗读录进去
                        listenAnswer()
                    }
                    is AskOutcome.NeedManual -> publish(out.hint, question = null, hint = out.hint)
                    is AskOutcome.Waited -> Unit
                    is AskOutcome.Busy -> Unit
                }
            }
        }
    }

    // ---- 认目标、认跳播 ----

    /**
     * 这一场到底在听谁。
     *
     * 开局时用户还停在一手，拿不到目标应用；等媒体会话报出正在放音的 App 再认，
     * 比让用户手填包名可靠。换 App 就当换了一段，旧问题作废。
     */
    private fun watchSource() {
        val m = media ?: return
        // 前台那个 App 又确实在放音，才认它；否则挑一个正在放音的会话，避免把后台音乐当成目标
        val fg = com.yishou.app.gate.GateService.foreground
        val playing = m.playingPackages()
        val pick = fg?.takeIf { it != packageName && it in playing } ?: playing.firstOrNull()
        if (pick != null && pick != pkg) adopt(pick)
        val target = pkg
        if (target.isBlank()) return
        val pos = m.positionOf(target) ?: return
        val tl = timeline ?: return
        if (tl.observePosition(pos, elapsed())) {
            session?.sourceChanged()
            publish("跳播了：开新的一段，刚才那一问作废")
        }
    }

    private fun adopt(newPkg: String) {
        stopListening()
        val first = pkg.isBlank()
        pkg = newPkg
        if (sessionId > 0) {
            val id = sessionId
            store?.let { s -> app.appScopeLaunch { s.setPkg(id, newPkg) } }
        }
        if (!first) {
            session?.sourceChanged()
            timeline?.bumpEpoch()
            publish("换成了${appLabel(newPkg)}，开新的一段")
        } else {
            publish("在听${appLabel(newPkg)}")
        }
        // 认出来以后把内录收窄到这个 App，别把别的 App 的播放当材料
        val uid = runCatching { packageManager.getApplicationInfo(newPkg, 0).uid }.getOrNull() ?: return
        val proj = projection ?: return
        val a = audio ?: return
        app.appScopeLaunch {
            val err = a.rebind(proj, scope, uid)
            if (err != null) {
                // 收窄失败就退回「所有 App 的声音」：宁可范围大一点，也不能一场没声音
                RunLog.e("视频陪练", "按目标 UID 重建内录失败：$err")
                val fallback = a.start(proj, scope, null)
                publish(
                    if (fallback == null) "听不到这个 App 的声音了？先按「所有 App」收着"
                    else "内录没恢复：$fallback",
                )
            }
        }
    }

    private fun appLabel(p: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    } catch (e: Exception) {
        p
    }

    // ---- 语音作答 ----

    /**
     * 问题念完、在等回答时才开麦；说完自动停（VAD），也可以手动「说完了」。
     *
     * 听不清、听写失败都退回打字，不记成答错；说出口的那句和打字走同一条 [answer]。
     */
    private fun listenAnswer() {
        val sess = session ?: return
        if (!VoiceAnswer.shouldListen(sess.phase())) return
        if (listening?.isActive == true) return
        listening = scope.launch {
            val heard = app.voice.listen()
            if (!isActive) return@launch
            when (heard) {
                is Heard.Text -> {
                    val text = VoiceAnswer.accept(heard.text)
                    if (text == null) publish(VoiceAnswer.missed()) else answer(text, fromVoice = true)
                }
                Heard.Nothing -> publish(VoiceAnswer.missed())
                is Heard.Failed -> publish(VoiceAnswer.missed(heard.message))
            }
        }
    }

    /** 不在等回答时把麦关掉：念问题、判答案、换段、停掉都不该继续录 */
    private fun stopListening() {
        listening?.cancel()
        listening = null
        app.voice.cancel()
    }

    // ---- 界面动作 ----

    private fun answer(text: String, fromVoice: Boolean = false) {
        val co = conductor ?: return
        if (text.isBlank()) return
        // 打字作答时把麦收掉；语音那条路已经自己结束了，别再取消自己
        if (fromVoice) listening = null else stopListening()
        val material = timeline?.slice(elapsed(), System.currentTimeMillis() + 20_000)?.text.orEmpty()
        scope.launch {
            val r = co.answer(text, material)
            if (r == null) {
                publish("现在不是在等你答，这句没记")
            } else {
                publish(
                    when {
                        r.verdict.counted -> "${r.verdict.label}：${r.delta.ifBlank { "这次没具体指哪一处" }}"
                        else -> "这次没判成，不算你答错"
                    },
                    question = null,
                )
                // 下次出题要接上这次的差值
                memory = store?.weakMemory(3).orEmpty()
            }
        }
    }

    private fun ready() {
        val co = conductor ?: return
        scope.launch {
            when (val out = co.userReady()) {
                is AskOutcome.Asked -> publish("", question = out.question, hint = null)
                is AskOutcome.Waited -> publish(out.reason)
                else -> Unit
            }
        }
    }

    private fun skip() {
        val sess = session ?: return
        stopListening()
        sess.dropQuestion()
        publish("这一问跳过了（不算答错）", question = null, hint = null)
    }

    private fun capsuleState(): CapsuleState = CapsuleState(
        sound = audio?.let { if (it.heard()) true else if (it.totalMs() > 0) false else null },
        frames = frames?.let { if (it.seen > 0) true else null },
        activeMs = elapsed(),
        phase = session?.phase() ?: com.yishou.app.companion.core.Phase.OBSERVE,
        soundNote = audio?.let { if (it.heard()) "" else "这段时间没有声音" } ?: "",
        frameNote = frames?.let { if (it.seen > 0) "" else "还没收到画面" } ?: "",
    )

    private fun elapsed(): Long = (System.currentTimeMillis() - startedWall).coerceAtLeast(0)

    // ---- 收摊 ----

    private fun stopAll() {
        if (stopped) return
        stopped = true
        stopListening()
        ticker?.cancel()
        ticker = null
        tap?.flush()
        tap?.stop()
        tap = null
        audio?.stop()
        frames?.stop()
        conductor?.stop()
        speaker?.shutdown()
        speaker = null
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (e: Exception) {
            RunLog.e("视频陪练", "停投屏出错", e)
        }
        CaptureArbiter.release(CaptureHolder.COMPANION)
        val id = sessionId
        val sec = (elapsed() / 1_000).toInt()
        val a = asked
        val m = missed
        val st = store
        scope.launch {
            if (id > 0 && st != null) st.closeSession(id, sec, a, m)
        }
        publish("这一场结束了：问了 $asked 次，没接 $missed 次", running = false, question = null, hint = null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped) stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 71, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "companion"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 72, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 人还在别的 App 里看视频，不能要求他切回来才能结束说话
        val done = PendingIntent.getService(
            this, 75, Intent(this, CompanionService::class.java).setAction(ACTION_VOICE_DONE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("一手 · 视频陪练")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "说完了", done)
            .addAction(0, "停", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun publish(
        note: String = "",
        running: Boolean = _view.value.running,
        question: String? = _view.value.question,
        hint: String? = _view.value.hint,
    ) {
        _view.value = View(
            running = running,
            capsule = note.ifBlank { _view.value.capsule },
            note = note,
            question = question,
            hint = hint,
            phase = session?.phase()?.label.orEmpty(),
        )
        try {
            getSystemService(NotificationManager::class.java).notify(ID, notification(note.ifBlank { _view.value.capsule }))
        } catch (e: SecurityException) { }
    }

    data class View(
        val running: Boolean = false,
        val capsule: String = "",
        val note: String = "",
        val question: String? = null,
        val hint: String? = null,
        val phase: String = "",
    )

    companion object {
        private const val CHANNEL = "companion"
        private const val ID = 81
        private const val ACTION_ANSWER = "answer"
        private const val ACTION_READY = "ready"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_VOICE_DONE = "voice_done"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_GEAR = "gear"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_TEXT = "text"

        private val _view = MutableStateFlow(View())
        val view: StateFlow<View> = _view.asStateFlow()

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "视频陪练", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        fun start(context: Context, code: Int, data: Intent, goal: String, minutes: Int, gear: String, pkg: String) {
            context.startForegroundService(
                Intent(context, CompanionService::class.java)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_GOAL, goal)
                    .putExtra(EXTRA_MINUTES, minutes)
                    .putExtra(EXTRA_GEAR, gear)
                    .putExtra(EXTRA_PKG, pkg),
            )
        }

        private fun action(context: Context, a: String, text: String? = null) {
            val i = Intent(context, CompanionService::class.java).setAction(a)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            context.startService(i)
        }

        fun answer(context: Context, text: String) = action(context, ACTION_ANSWER, text)
        fun ready(context: Context) = action(context, ACTION_READY)
        fun skip(context: Context) = action(context, ACTION_SKIP)

        /** 说完了：通知栏就能结束这一次收音，不用切回一手 */
        fun voiceDone(context: Context) = action(context, ACTION_VOICE_DONE)
        fun stop(context: Context) = action(context, ACTION_STOP)
    }
}
