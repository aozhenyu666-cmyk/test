package com.yishou.app.scroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.data.MaterialSource
import com.yishou.app.data.ScrollQuizRow
import com.yishou.app.gate.GateService
import com.yishou.app.llm.LlmResult
import com.yishou.app.log.RunLog
import com.yishou.app.settings.AppZone
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 刷着练：你在系统弹窗里同意看屏后，一手在后台保持一个低分辨率的屏幕镜像。
 * 你点“考我一下”（通知栏、快捷开关），或者在关注的应用里刷满设定的分钟数，才截这一屏
 * （连拍三帧看清字幕），交给识图模型出一问；你在通知里直接回一句，判完接着刷。
 *
 * 截图不保存，只发给你自己填的识图接口。一次最长 60 分钟，通知栏一直有“停”。
 */
class ScrollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as YishouApp

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var latest: Image? = null
    private val lock = Any()

    private var startedAt = 0L
    private val pace = AutoPace(0)
    private var quiz: Quiz? = null
    /** 这一问在库里的行号（问出来就记，回了再补上回答和判定） */
    private var quizId: Long? = null
    /** 回的是套话：正在追问的那一串（一刀一刀在通知里回） */
    private var drill: com.yishou.app.sharp.Drill? = null
    private var askJob: Job? = null
    private var judgeJob: Job? = null
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_ASK -> ask(intent.getLongExtra(EXTRA_DELAY, 0), auto = false)
            ACTION_REPLY -> {
                val expected = intent.getLongExtra("panel_quiz", -1)
                if (expected > 0 && expected != quizId) android.widget.Toast.makeText(this, "已经换题了，请回复现在这一问", android.widget.Toast.LENGTH_SHORT).show()
                else reply(intent.getStringExtra("panel_answer") ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty())
            }
            ACTION_REVEAL -> reply("不知道", reveal = true)
            ACTION_SKIP -> skip()
            ACTION_INTERVAL -> {
                pace.setInterval(prefs(this).getInt(KEY_INTERVAL, DEFAULT_INTERVAL))
                if (projection != null) refresh()
            }
            ACTION_STOP -> stopAll()
        }
        // 已经停了，又点了旧通知上的按钮：处理完就退出
        if (projection == null && intent?.action != ACTION_START && judgeJob?.isActive != true) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        ensureChannels(this)
        startedAt = System.currentTimeMillis()
        val n = runningNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID_RUNNING, n)
        }
        if (projection != null) return

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val p = try {
            data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        } catch (e: Exception) {
            RunLog.e("刷着练", "无法获取屏幕授权", e)
            null
        }
        if (p == null) {
            stopAll()
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopAll() }
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val w = 720
        val h = (metrics.heightPixels.toLong() * w / metrics.widthPixels).toInt()
        val thread = HandlerThread("yishou-scroll").also { it.start() }
        imageThread = thread
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                latest?.close()
                latest = img
            }
        }, Handler(thread.looper))
        reader = r
        display = p.createVirtualDisplay(
            "yishou-scroll", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
        pace.setInterval(prefs(this).getInt(KEY_INTERVAL, DEFAULT_INTERVAL))
        _running.value = true
        refresh()
        watch()
    }

    /** 每 15 秒看一眼：在关注的应用里刷满时间就问；到 60 分钟自动停。 */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                if (now - startedAt >= SESSION_MS) {
                    stopAll()
                    break
                }
                val fg = GateService.foreground
                val watching = fg != null && app.settings.app.value.groupOf(fg) != null
                if (pace.tick(now, watching) && askJob?.isActive != true && judgeJob?.isActive != true) ask(0, auto = true)
            }
        }
    }

    private fun ask(delayMs: Long, auto: Boolean) {
        if (projection == null) return
        if (askJob?.isActive == true) return
        askJob = scope.launch {
            // 从通知栏点的：等通知栏收起、底下的应用露出来再截
            if (delayMs > 0) delay(delayMs)
            val pkg = GateService.foreground.orEmpty()
            setStatus("正在看这一屏……")
            val dataUrl = com.yishou.app.floating.CoachFloat.withoutPanel { burst() }
            if (dataUrl == null) {
                setStatus(if (auto) null else "没截到画面，再点一次")
                return@launch
            }
            val sp = prefs(this@ScrollService)
            val index = sp.getInt(KEY_LENS, 0)
            val lens = Lens.at(index)
            val r = app.scrollLook.completeWithImage(ScrollQuiz.lookSystem(lens), ScrollQuiz.LOOK_USER, dataUrl, "刷着练出题")
            val look = when (r) {
                is LlmResult.Err -> {
                    setStatus("识图出错：${r.error.message}")
                    return@launch
                }
                is LlmResult.Ok -> ScrollQuiz.parseLook(r.value, lens)
            }
            when (look) {
                null -> setStatus(if (auto) null else "这屏没出好题，再点一次")
                is Look.Skip -> {
                    // 自动的：安静地重新计时；手动的：说一声
                    pace.asked(System.currentTimeMillis())
                    pace.answered()
                    setStatus(if (auto) null else "这屏不考：${look.reason}")
                }
                is Look.Ask -> {
                    sp.edit().putInt(KEY_LENS, index + 1).apply()
                    quiz = look.quiz
                    val q = look.quiz
                    quizId = app.database.scrollQuizzes().insert(
                        ScrollQuizRow(
                            askedAt = System.currentTimeMillis(), pkg = pkg, lens = q.lens.name, seen = q.seen,
                            question = q.question, reference = q.reference, text = q.text,
                        ),
                    )
                    pace.asked(System.currentTimeMillis())
                    app.stats.event(EVENT_ASK)
                    setStatus(null)
                    showQuiz(look.quiz)
                }
            }
        }
    }

    /**
     * 回了一句。套话（“太绝对了”“不知道”“有道理”）不直接判，先在通知里追问两三刀：
     * 照抄屏幕上的哪几个字、举个具体例子、改成什么样才站得住。切完再判，而且最多判“差一点”。
     * 点“看参考”是认了说不出：直接给参考，不追问。
     */
    private fun reply(answer: String, reveal: Boolean = false) {
        val q = quiz ?: run {
            cancelQuiz()
            return
        }
        val d = drill
        if (!reveal && d != null) {
            d.reply(answer)
            if (!d.done) {
                showCut(q, d)
                return
            }
            drill = null
            finish(q, d.transcript(), chased = true)
            return
        }
        drill = null
        if (!reveal) {
            val h = com.yishou.app.sharp.Sharp.hollow(answer)
            if (h != null) {
                val nd = com.yishou.app.sharp.Drill(answer, com.yishou.app.sharp.Sharp.cuts(h, q.text.isNotBlank()), q.text)
                drill = nd
                showCut(q, nd)
                return
            }
        }
        finish(q, answer, chased = false)
    }

    private fun finish(q: Quiz, answer: String, chased: Boolean) {
        val id = quizId
        quiz = null
        quizId = null
        pace.answered()
        // 先把通知换掉，不然回复框会一直转圈
        notifyQuiz(simple(q.question, "判着……", silent = true))
        setStatus("正在核对你的回答……")
        judgeJob = scope.launch {
            try {
                judge(q, answer, id, chased)
            } finally {
                if (projection == null) stopSelf()
            }
        }
    }

    private suspend fun judge(q: Quiz, answer: String, id: Long?, chased: Boolean) {
        val quick = if (chased) null else ScrollQuiz.quick(answer)
        val raw = quick ?: when (val r = app.scrollJudge.complete(ScrollQuiz.judgeSystem(), ScrollQuiz.judgeUser(q, answer), "刷着练判")) {
            is LlmResult.Ok -> ScrollQuiz.parseJudge(r.value) ?: Judged(Verdict.NEAR, "")
            is LlmResult.Err -> null
        }
        // 被追问出来的不算一击即中
        val judged = if (chased && raw?.verdict == Verdict.HIT) Judged(Verdict.NEAR, "追问出来的，下次第一句就说到这儿。" + raw?.line.orEmpty()) else raw
        val dao = app.database.scrollQuizzes()
        val now = System.currentTimeMillis()
        if (judged == null) {
            id?.let { dao.answer(it, answer.trim(), "", "", now) }
            notifyQuiz(result(q, answer, null))
            setStatus("这次没能判成。参考：${q.reference}")
            return
        }
        id?.let { dao.answer(it, answer.trim(), judged.verdict.name, judged.line, now) }
        app.stats.event(
            when (judged.verdict) {
                Verdict.HIT -> EVENT_HIT
                Verdict.NEAR -> EVENT_NEAR
                Verdict.MISS -> EVENT_MISS
            },
        )
        notifyQuiz(result(q, answer, judged))
        setStatus(ScrollQuiz.feedback(q, judged))
        // 认真答过的这一屏，原文收进素材池，以后“来一手”也能拿来练
        if (quick == null) ScrollQuiz.material(q)?.let { app.pool.take(it, MaterialSource.SCREEN) }
    }

    private fun skip() {
        val id = quizId
        drill = null
        quiz = null
        quizId = null
        if (id != null) app.appScopeLaunch { app.database.scrollQuizzes().answer(id, "", SKIP, "", System.currentTimeMillis()) }
        pace.answered()
        cancelQuiz()
    }

    // ---- 截屏 ----

    /** 连拍三帧（隔 1 秒），一样的去掉，左右拼成一张。拍不到返回 null。 */
    private suspend fun burst(): String? {
        val shots = mutableListOf<Bitmap>()
        repeat(FRAMES) { i ->
            if (i > 0) delay(FRAME_GAP_MS)
            val b = grab() ?: return@repeat
            if (shots.isNotEmpty() && shots.last().sameAs(b)) b.recycle() else shots += b
        }
        if (shots.isEmpty()) return null
        val gap = 8
        val width = shots.sumOf { it.width } + gap * (shots.size - 1)
        val height = shots.maxOf { it.height }
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        var x = 0f
        shots.forEach {
            canvas.drawBitmap(it, x, 0f, null)
            x += it.width + gap
            it.recycle()
        }
        val out = ByteArrayOutputStream()
        sheet.compress(Bitmap.CompressFormat.JPEG, 72, out)
        sheet.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 最新一帧缩到 360 宽 */
    private fun grab(): Bitmap? {
        val full = synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
            if (cropped !== padded) padded.recycle()
            cropped
        }
        val h = (full.height.toLong() * FRAME_WIDTH / full.width).toInt()
        val small = Bitmap.createScaledBitmap(full, FRAME_WIDTH, h, true)
        if (small !== full) full.recycle()
        return small
    }

    private fun stopAll() {
        com.yishou.app.floating.CoachFloat.remove("scroll")
        watchJob?.cancel()
        askJob?.cancel()
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        imageThread?.quitSafely()
        imageThread = null
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (e: Exception) {
            RunLog.e("刷着练", "停止看屏出错", e)
        }
        _running.value = false
        _status.value = null
        quiz = null
        quizId = null
        drill = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 判卷还在跑就让它判完再退出（结果通知照样发）；没在判的题收掉
        if (judgeJob?.isActive == true) return
        cancelQuiz()
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) stopAll()
        scope.cancel()
        super.onDestroy()
    }

    // ---- 通知 ----

    private fun setStatus(text: String?) {
        _status.value = text
        refresh()
    }

    private fun refresh() {
        if (projection == null) return
        notifyNow(ID_RUNNING, runningNotification(_status.value))
        com.yishou.app.floating.CoachFloat.update(this, "scroll", com.yishou.app.floating.FloatPanel("刷着练", _status.value ?: quiz?.question ?: "点一下，围绕当前这一屏问一题",
            listOf(
                com.yishou.app.floating.FloatAction("考我一下") { ask(400, auto = false) },
                com.yishou.app.floating.FloatAction("回一句") { quiz?.let { com.yishou.app.floating.FloatingReplyActivity.open(this, "scroll", drill?.current?.ask ?: it.question, quizId ?: -1) } },
                com.yishou.app.floating.FloatAction("停止") { stopAll() },
            ), priority = 10))
    }

    private fun runningNotification(status: String?): Notification {
        val interval = pace.intervalMinutes
        val until = AppZone.format("HH:mm", startedAt + SESSION_MS)
        val text = (if (interval > 0) "在关注的应用里每刷 $interval 分钟问你一句；" else "") +
            "想练就点“考我一下”。截图不保存，$until 自动停。"
        return NotificationCompat.Builder(this, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(status ?: "刷着练开着")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "考我一下", ScrollActivity.askPending(this))
            .addAction(0, "停", service(ACTION_STOP, 41))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun replyAction(label: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("一句话回").build()
        val replyIntent = Intent(this, ScrollService::class.java).setAction(ACTION_REPLY)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getService(this, 42, replyIntent, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, replyPi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /** 追问的一刀：同一条通知原地换内容，不再响 */
    private fun showCut(q: Quiz, d: com.yishou.app.sharp.Drill) {
        val cut = d.current ?: return
        setStatus((d.hint?.let { "$it\n" } ?: "") + cut.ask)
        val body = buildString {
            d.hint?.let { append(it).append("\n") }
            append(cut.ask)
            append("\n\n问的是：").append(q.question)
            append("\n你先说的：").append(d.first.trim())
        }
        val n = NotificationCompat.Builder(this, CH_QUIZ)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("追问 ${d.index + 1}/${d.cuts.size}")
            .setContentText(d.hint ?: cut.ask)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(replyAction("回这一刀"))
            .addAction(0, "看参考", service(ACTION_REVEAL, 43))
            .addAction(0, "跳过", service(ACTION_SKIP, 44))
            .setDeleteIntent(service(ACTION_SKIP, 45))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(AutoPace.PENDING_MS)
            .build()
        notifyQuiz(n)
    }

    private fun showQuiz(q: Quiz) {
        val replyAction = replyAction("回一句")
        val body = q.question + if (q.seen.isNotBlank()) "\n刷到：${q.seen}" else ""
        val n = NotificationCompat.Builder(this, CH_QUIZ)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("考你一下 · ${q.lens.label}")
            .setContentText(q.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(replyAction)
            .addAction(0, "看参考", service(ACTION_REVEAL, 43))
            .addAction(0, "跳过", service(ACTION_SKIP, 44))
            .setDeleteIntent(service(ACTION_SKIP, 45))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(AutoPace.PENDING_MS)
            .build()
        notifyQuiz(n)
    }

    private fun result(q: Quiz, answer: String, j: Judged?): Notification {
        val title = j?.verdict?.label ?: "这次没判成"
        val body = buildString {
            append(q.question)
            if (answer.isNotBlank() && answer != "不知道") append("\n你说：").append(answer.trim())
            append("\n\n")
            if (j != null) append(ScrollQuiz.feedback(q, j)) else append("判卷接口出错了。参考：").append(q.reference)
        }
        val b = NotificationCompat.Builder(this, CH_QUIZ)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(title)
            .setContentText(body.lineSequence().drop(1).firstOrNull { it.isNotBlank() } ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
        if (projection != null) b.addAction(0, "再来一题", ScrollActivity.askPending(this))
        b.addAction(0, "去 AI 里接着聊", ScrollActivity.deepPending(this, ScrollQuiz.deepPrompt(q, answer.takeIf { it != "不知道" }, j)))
        return b.build()
    }

    private fun simple(title: String, text: String, silent: Boolean): Notification =
        NotificationCompat.Builder(this, CH_QUIZ)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(title)
            .setContentText(text)
            .setSilent(silent)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyQuiz(n: Notification) = notifyNow(ID_QUIZ, n)

    private fun cancelQuiz() = NotificationManagerCompat.from(this).cancel(ID_QUIZ)

    private fun notifyNow(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            RunLog.e("刷着练", "通知发不出去", e)
        }
    }

    private fun service(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, ScrollService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 46, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CH_RUNNING = "scroll"
        private const val CH_QUIZ = "scroll_quiz"
        private const val ID_RUNNING = 40
        private const val ID_QUIZ = 41
        private const val ACTION_START = "start"
        private const val ACTION_ASK = "ask"
        private const val ACTION_REPLY = "reply"
        private const val ACTION_REVEAL = "reveal"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_INTERVAL = "interval"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_DELAY = "delay"
        private const val KEY_REPLY = "answer"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_LENS = "lens"

        const val DEFAULT_INTERVAL = 5
        private const val SESSION_MS = 60 * 60_000L
        private const val TICK_MS = 15_000L
        private const val FRAMES = 3
        private const val FRAME_GAP_MS = 1_000L
        private const val FRAME_WIDTH = 360

        const val SKIP = "SKIP"
        const val EVENT_ASK = "scroll_ask"
        const val EVENT_HIT = "scroll_hit"
        const val EVENT_NEAR = "scroll_near"
        const val EVENT_MISS = "scroll_miss"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _status = MutableStateFlow<String?>(null)
        val status: StateFlow<String?> = _status.asStateFlow()

        fun prefs(context: Context) = context.getSharedPreferences("scroll", Context.MODE_PRIVATE)

        fun interval(context: Context): Int = prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)

        fun setInterval(context: Context, minutes: Int) {
            prefs(context).edit().putInt(KEY_INTERVAL, minutes).apply()
            if (running.value) context.startService(Intent(context, ScrollService::class.java).setAction(ACTION_INTERVAL))
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_RUNNING, "刷着练", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "刷着练开着时的常驻通知，带“考我一下”和“停”"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_QUIZ, "刷着练的提问", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "刷到一半弹出的一问，可以直接在通知里回"
                },
            )
        }

        /** 用户在系统弹窗里同意之后调用 */
        fun replyFromPanel(context: Context, text: String, quiz: Long) {
            context.startService(Intent(context, ScrollService::class.java).setAction(ACTION_REPLY).putExtra("panel_answer", text).putExtra("panel_quiz", quiz))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScrollService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun ask(context: Context, delayMs: Long) {
            context.startService(Intent(context, ScrollService::class.java).setAction(ACTION_ASK).putExtra(EXTRA_DELAY, delayMs))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScrollService::class.java).setAction(ACTION_STOP))
        }
    }
}
