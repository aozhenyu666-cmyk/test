package io.github.ccch1mneyyy.dsh_app.loop.driver

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface

/**
 * dsh-driver-core 的手机端：传感器 + 执行器。判断交给插件，这里只做两件不能等的事：
 *
 *  1. **反射**：锁定期间切出允许的应用 → 立刻盖上缓存的断点卡（不等网络、不等模型）。
 *  2. **执行指令**：插件发来的 show_card / pullback / escalate / release / end。
 *
 * 接线（在 fork 里改两处）：
 *  - `LoopRuntime.verifyNow()` 里调用 `InterceptRuntime.onForeground(pkg)` 的地方，紧挨着加
 *    `DriverRuntime.onForeground(app, pkg)`。
 *  - `MainActivity.configureFlutterEngine` 里 `DriverRuntime.attach(context, port)`，port 由
 *    `dsh/driver` MethodChannel 实现（见 ../dart/driver_channel.dart）。
 *
 * 未编译进 fork、未真机：见 docs/02 的验证清单。
 */
object DriverRuntime {
    private const val TAG = "DshDriver"
    const val OWNER_DRIVER = "driver"

    /** Kotlin → Dart → 插件。Dart 侧持有引擎 cookie（和 dsh-go-proactive 同一条路）。 */
    interface Port {
        fun signal(kind: String, fields: Map<String, Any?>)
    }

    data class Card(
        val id: String,
        val kind: String,
        val text: String,
        val optionA: String?,
        val optionB: String?,
        val level: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var port: Port? = null
    private var signals: DriverSignals? = null
    private val live = LiveInference()

    @Volatile var sessionId: String? = null
        private set
    @Volatile private var locked = false
    @Volatile private var paused = false
    private var anchors: Set<String> = emptySet()
    private var anchorPkg: String? = null
    @Volatile var card: Card? = null
        private set
    private var level = 0

    fun attach(context: Context, p: Port) {
        app = context.applicationContext
        port = p
    }

    /** Dart 开了一次锁定之后调用（插件已返回 sessionId 和允许的应用）。 */
    fun onSessionStarted(id: String, allowed: List<String>) {
        sessionId = id
        anchors = allowed.toSet()
        anchorPkg = allowed.firstOrNull()
        locked = true
        paused = false
        level = 0
        signals?.stop()
        signals = DriverSignals(app) { rec, play -> onAudio(rec, play) }.also { it.start() }
        main.post(livePoll)
    }

    // ───────────── 反射 ─────────────

    fun onForeground(context: Context, pkg: String?) {
        if (!::app.isInitialized) return
        val imes = runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }?.toSet()
        }.getOrNull().orEmpty()
        when (val v = DriverReflex.judge(pkg, locked && !paused, anchors, app.packageName, imes)) {
            DriverReflex.Verdict.Ignore -> Unit
            is DriverReflex.Verdict.Allow -> {
                if (v.anchor) anchorPkg = v.packageName
                send("foreground", mapOf("pkg" to v.packageName, "allowed" to true))
            }
            is DriverReflex.Verdict.Escape -> {
                // 先盖卡，再上报：上报失败也已经拦住了
                val c = card
                if (c != null) DriverCardActivity.start(app, c.copy(level = maxOf(1, level)), reflex = true)
                else DriverCardActivity.startBare(app, anchorPkg)
                send("foreground", mapOf("pkg" to v.packageName, "allowed" to false))
            }
        }
    }

    private fun onAudio(recording: Boolean, assistantPlaying: Boolean) {
        for (c in live.onAudio(recording, assistantPlaying, System.currentTimeMillis())) emit(c)
    }

    private val livePoll = object : Runnable {
        override fun run() {
            if (!locked) return
            for (c in live.poll(System.currentTimeMillis())) emit(c)
            main.postDelayed(this, 2_000L)
        }
    }

    private fun emit(c: LiveInference.Change) = when (c) {
        is LiveInference.Change.Live -> send("live", mapOf("active" to c.active))
        LiveInference.Change.AssistantStartedSpeaking -> send("audio", mapOf("assistantSpeaking" to true))
    }

    // ───────────── 指令 ─────────────

    /** Dart 长轮询拿到的一条指令（插件 JSON 原样转成 Map）。 */
    fun onDirective(d: Map<String, Any?>) = main.post {
        val type = d["type"] as? String ?: return@post
        level = (d["level"] as? Number)?.toInt() ?: level
        (d["relaunch"] as? String)?.let { anchorPkg = it }
        val c = cardOf(d["card"])
        if (c != null) card = c
        when (type) {
            "show_card" -> c?.let { if (it.level >= 1 || DriverOverlay.canShow(app).not()) DriverCardActivity.start(app, it, reflex = false) else DriverOverlay.show(app, it) }
            "pullback" -> c?.let { DriverCardActivity.start(app, it, reflex = false) }
            "escalate" -> c?.let { DriverCardActivity.start(app, it.copy(level = 2), reflex = false) }
            "release" -> { paused = true; DriverOverlay.hide(); DriverCardActivity.withdraw() }
            "end" -> end()
        }
        if (type == "pullback" || type == "show_card") paused = false
    }

    private fun end() {
        locked = false
        paused = false
        sessionId = null
        card = null
        signals?.stop()
        signals = null
        main.removeCallbacks(livePoll)
        DriverOverlay.hide()
        DriverCardActivity.withdraw()
        VisibleSurface.holderToken()?.let { VisibleSurface.release(OWNER_DRIVER, it) }
    }

    private fun cardOf(raw: Any?): Card? {
        val m = raw as? Map<*, *> ?: return null
        val opts = m["options"] as? Map<*, *>
        return Card(
            id = m["id"] as? String ?: return null,
            kind = m["kind"] as? String ?: "open",
            text = m["text"] as? String ?: return null,
            optionA = opts?.get("A") as? String,
            optionB = opts?.get("B") as? String,
            level = (m["level"] as? Number)?.toInt() ?: 0,
        )
    }

    // ───────────── 卡片回报 ─────────────

    fun onCardShown(cardId: String, via: String) = send("surface", mapOf("cardId" to cardId, "status" to "displayed", "via" to via))
    fun onCardNotShown(cardId: String, via: String) = send("surface", mapOf("cardId" to cardId, "status" to "not_shown", "via" to via))
    fun onReply(cardId: String, text: String?, choice: String?, channel: String) =
        send("reply", mapOf("cardId" to cardId, "text" to text, "choice" to choice, "channel" to channel))
    /** 我们自己在播 TTS：别把自己的声音当成 Gemini 在说话。 */
    fun selfSpeaking(on: Boolean) { live.selfSpeaking = on }
    fun onPause(minutes: Int) = send("pause", mapOf("minutes" to minutes))
    fun onEmergencyStop() = send("stop", mapOf("reason" to "emergency")).also { end() }

    /** 回到 Live：打开锚点应用。有悬浮窗权限时系统允许后台启动（Android 10+ 的 BAL 豁免）。 */
    fun relaunchAnchor(context: Context): Boolean {
        val pkg = anchorPkg ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "could not relaunch $pkg", it) }.isSuccess
    }

    private fun send(kind: String, fields: Map<String, Any?>) {
        val id = sessionId ?: return
        runCatching { port?.signal(kind, fields + mapOf("sessionId" to id, "at" to System.currentTimeMillis())) }
            .onFailure { Log.w(TAG, "signal $kind failed", it) }
    }
}

/** 系统音频状态监听：只读"有没有在录 / 有没有助手类播放"。 */
class DriverSignals(context: Context, private val onChange: (recording: Boolean, assistantPlaying: Boolean) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val rec = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) = report()
    }
    private val play = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) = report()
    }

    fun start() {
        am?.registerAudioRecordingCallback(rec, handler)
        am?.registerAudioPlaybackCallback(play, handler)
        report()
    }

    fun stop() {
        am?.unregisterAudioRecordingCallback(rec)
        am?.unregisterAudioPlaybackCallback(play)
    }

    private fun report() {
        val a = am ?: return
        val recording = a.activeRecordingConfigurations.isNotEmpty()
        val playing = a.activePlaybackConfigurations.any { it.audioAttributes.usage in VOICE_USAGES }
        onChange(recording, playing)
    }

    private companion object {
        val VOICE_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_MEDIA, // 部分版本的 Live 走媒体流；锁定期间前台只有 Live，误判空间小
        )
    }
}
