package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VoicePlan
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette
import io.github.ccch1mneyyy.dsh_app.pet.dp
import io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeed
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist

/**
 * Pure JVM-testable decision policy for proactive Gemini co-brain launches.
 */
sealed interface LaunchDecision {
    data class Defer(val reason: String) : LaunchDecision
    data object LaunchNow : LaunchDecision
    data class CountdownOverlay(val seconds: Int = 3) : LaunchDecision
}

object GeminiLaunchPolicy {
    fun decide(
        frontPkg: String?,
        emergencyActive: Boolean,
        pullMode: String,
        homePackages: Set<String> = emptySet(),
    ): LaunchDecision {
        if (emergencyActive) return LaunchDecision.Defer("safe_or_emergency")
        val pkg = frontPkg?.trim().orEmpty()
        if (pkg.isNotEmpty() && pkg in SystemSafeAllowlist.EMERGENCY_PACKAGES) {
            return LaunchDecision.Defer("safe_or_emergency")
        }
        return when (pullMode.trim().lowercase()) {
            "instant" -> LaunchDecision.LaunchNow
            "gentle" -> LaunchDecision.Defer("gentle_mode")
            else -> {
                // Keep homePackages in the signature for policy parity; launching from home or any
                // non-emergency foreground defaults to the 3s Mist Glass countdown bridge.
                if (pkg.isNotEmpty() && pkg in homePackages) {
                    LaunchDecision.CountdownOverlay(seconds = 3)
                } else {
                    LaunchDecision.CountdownOverlay(seconds = 3)
                }
            }
        }
    }

    /** Routes Gemini "正事延后 10m" through the shared [DisarmCapPolicy] emergency disarm cap. */
    fun planDefer10m(
        day: String,
        storedDay: String,
        storedUsed: Int,
        storedSeq: Int,
        cap: Int = DisarmCapPolicy.DEFAULT_DAILY_CAP,
    ): DisarmCapPolicy.DisarmPlan = DisarmCapPolicy.planDisarm(
        day = day,
        storedDay = storedDay,
        storedUsed = storedUsed,
        storedSeq = storedSeq,
        cap = cap,
        reason = "Gemini倒计时延后10分钟",
        counted = true,
        source = "gemini-defer",
    )
}

/**
 * Android 10–15 BAL-exempt proactive Gemini launcher:
 * 1) Preloads brief into ClipboardManager;
 * 2) Optionally displays a 3-second Mist Glass SYSTEM_ALERT_WINDOW bridge card (which also
 *    provides BAL exemption when overlay permission is granted);
 * 3) Launches via live AccessibilityService context first, falling back to Context.startActivity
 *    paired with an IMPORTANCE_HIGH fullScreenIntent notification so OEM ROMs never drop it.
 */
object GeminiAutoLauncher {
    private const val TAG = "DshGeminiAuto"
    private const val CHANNEL_ID = "dsh-gemini-proactive"
    private const val NOTIF_TAG = "dsh-gemini-pull"
    private const val NOTIF_ID = 7401
    private val GEMINI_PACKAGES = listOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
    )

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var liveCard: CountdownCard? = null

    fun launch(
        app: Context,
        topic: String,
        brief: String,
        mode: String = "countdown_3s",
        preferVoice: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val appCtx = app.applicationContext
        val runOnMain = Runnable {
            launchOnMain(appCtx, topic, brief, mode, preferVoice, onResult)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            main.post(runOnMain)
        }
    }

    /**
     * 超级大肥鱼 v4：物理震动 + 本地 TTS 破冰第一枪 + 写剪贴板 + 打开官方 Gemini +
     * 若 [autoClickLive] 为真且无障碍在线，触发 4 连击自动灌料并点进 Gemini Live。
     */
    fun launchAndAutomateLive(
        app: Context,
        topic: String = "",
        brief: String = "",
        voiceLine: String = "",
        mode: String = "instant",
        autoClickLive: Boolean = true,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val appCtx = app.applicationContext ?: app
        val runOnMain = Runnable {
            launchAndAutomateLiveOnMain(
                app = appCtx,
                topic = topic,
                brief = brief,
                voiceLine = voiceLine,
                mode = mode,
                autoClickLive = autoClickLive,
                onResult = onResult,
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runOnMain.run()
        } else {
            main.post(runOnMain)
        }
    }

    private fun launchAndAutomateLiveOnMain(
        app: Context,
        topic: String,
        brief: String,
        voiceLine: String,
        mode: String,
        autoClickLive: Boolean,
        onResult: ((Boolean) -> Unit)?,
    ) {
        val frontPkg = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
        val emergencyActive = runCatching {
            ProactiveRuntime.inEmergencyPass() || LockSafety.disarmed(app)
        }.getOrDefault(false)
        val decision = GeminiLaunchPolicy.decide(
            frontPkg = frontPkg,
            emergencyActive = emergencyActive,
            pullMode = mode,
            homePackages = SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS,
        )
        if (decision is LaunchDecision.Defer) {
            Log.i(TAG, "launchAndAutomateLive deferred: reason=${decision.reason} front=$frontPkg")
            if (decision.reason == "gentle_mode") {
                copyBriefToClipboard(app, brief)
                val intents = buildCandidateIntents(app, preferVoice = false)
                if (intents.isNotEmpty()) {
                    postFallbackNotification(app, topic, intents.first(), fullScreen = false)
                }
            }
            onResult?.invoke(false)
            return
        }

        ProactiveHaptics.vibrateSuperSpark(app)
        if (voiceLine.isNotBlank()) {
            val spoken = VoicePlan.brief(voiceLine) ?: voiceLine.trim().take(VoicePlan.MAX_CHARS)
            if (spoken.isNotEmpty()) {
                runCatching {
                    VoiceOut.install(app)
                    VoiceOut.speak(VoicePlan(spoken, VoicePlan.DEFAULT_RATE, VoicePlan.DEFAULT_PITCH)) {}
                }
            }
        }
        copyBriefToClipboard(app, brief)

        fun startDirectAndMaybeAutomate() {
            val opened = startGeminiDirect(app, topic, preferVoice = false)
            if (opened && autoClickLive && PhoneA11yService.isConnected()) {
                GeminiUiAutomator.run(app, brief) { autoOk, _ ->
                    onResult?.invoke(autoOk)
                }
            } else {
                onResult?.invoke(opened)
            }
        }

        fun startWithProxyGate() {
            when (val step = ProxyGate.before(app)) {
                ProxyGate.Step.Direct -> startDirectAndMaybeAutomate()
                is ProxyGate.Step.OpenProxyFirst -> {
                    ProxyGate.openProxyThen(app, step.pkg) { startDirectAndMaybeAutomate() }
                }
            }
        }

        when (decision) {
            is LaunchDecision.LaunchNow -> {
                dismissOverlay()
                startWithProxyGate()
            }
            is LaunchDecision.CountdownOverlay -> {
                val host = resolveOverlayHost(app)
                if (host == null) {
                    startWithProxyGate()
                    return
                }
                dismissOverlay()
                val card = CountdownCard(
                    app = app,
                    ctx = host.first,
                    windowType = host.second,
                    topic = topic,
                    hasBrief = brief.isNotBlank(),
                    remainingSeconds = decision.seconds.coerceIn(1, 10),
                    onEnterNow = {
                        startWithProxyGate()
                        main.postDelayed({ dismissOverlay() }, 300L)
                    },
                    onDelay10m = {
                        dismissOverlay()
                        val res = LockSafety.disarm(
                            ctx = app,
                            reason = "Gemini倒计时延后10分钟",
                            minutes = LockSafety.DISARM_MINUTES,
                            counted = true,
                            source = "gemini-defer",
                        )
                        if (res is LockSafety.DisarmResult.Refused) {
                            main.post {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        app,
                                        "今天紧急解除/延后次数已用完：${res.why}",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        } else {
                            LockSafety.ensureNotification(app)
                        }
                        onResult?.invoke(false)
                    },
                )
                if (card.show()) {
                    liveCard = card
                } else {
                    startWithProxyGate()
                }
            }
            is LaunchDecision.Defer -> Unit
        }
    }

    private fun launchOnMain(
        app: Context,
        topic: String,
        brief: String,
        mode: String,
        preferVoice: Boolean,
        onResult: ((Boolean) -> Unit)?,
    ) {
        val frontPkg = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
        val emergencyActive = runCatching {
            ProactiveRuntime.inEmergencyPass() || LockSafety.disarmed(app)
        }.getOrDefault(false)
        val decision = GeminiLaunchPolicy.decide(
            frontPkg = frontPkg,
            emergencyActive = emergencyActive,
            pullMode = mode,
            homePackages = SystemSafeAllowlist.SYSTEM_UI_AND_LAUNCHERS,
        )

        if (decision is LaunchDecision.Defer) {
            Log.i(TAG, "launch deferred: reason=${decision.reason} front=$frontPkg")
            if (decision.reason == "gentle_mode") {
                copyBriefToClipboard(app, brief)
                val intents = buildCandidateIntents(app, preferVoice)
                if (intents.isNotEmpty()) {
                    postFallbackNotification(app, topic, intents.first(), fullScreen = false)
                }
            }
            onResult?.invoke(false)
            return
        }

        copyBriefToClipboard(app, brief)

        when (decision) {
            is LaunchDecision.LaunchNow -> {
                dismissOverlay()
                val ok = startGeminiActivity(app, topic, preferVoice)
                onResult?.invoke(ok)
            }
            is LaunchDecision.CountdownOverlay -> {
                val host = resolveOverlayHost(app)
                if (host == null) {
                    val ok = startGeminiActivity(app, topic, preferVoice)
                    onResult?.invoke(ok)
                    return
                }
                dismissOverlay()
                val card = CountdownCard(
                    app = app,
                    ctx = host.first,
                    windowType = host.second,
                    topic = topic,
                    hasBrief = brief.isNotBlank(),
                    remainingSeconds = decision.seconds.coerceIn(1, 10),
                    onEnterNow = {
                        val ok = startGeminiActivity(app, topic, preferVoice)
                        main.postDelayed({ dismissOverlay() }, 300L)
                        onResult?.invoke(ok)
                    },
                    onDelay10m = {
                        dismissOverlay()
                        val res = LockSafety.disarm(
                            ctx = app,
                            reason = "Gemini倒计时延后10分钟",
                            minutes = LockSafety.DISARM_MINUTES,
                            counted = true,
                            source = "gemini-defer",
                        )
                        if (res is LockSafety.DisarmResult.Refused) {
                            main.post {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        app,
                                        "今天紧急解除/延后次数已用完：${res.why}",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        } else {
                            LockSafety.ensureNotification(app)
                        }
                        onResult?.invoke(false)
                    },
                )
                if (card.show()) {
                    liveCard = card
                } else {
                    val ok = startGeminiActivity(app, topic, preferVoice)
                    onResult?.invoke(ok)
                }
            }
            is LaunchDecision.Defer -> Unit
        }
    }

    private fun copyBriefToClipboard(app: Context, brief: String) {
        if (brief.isBlank()) return
        runCatching {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("大肥鱼 · Gemini 共脑简报", brief))
        }.onFailure {
            Log.w(TAG, "clipboard copy failed", it)
        }
    }

    fun dismissOverlay() {
        liveCard?.destroy()
        liveCard = null
    }

    private fun resolveOverlayHost(app: Context): Pair<Context, Int>? {
        if (Settings.canDrawOverlays(app)) {
            val ctx = if (Build.VERSION.SDK_INT >= 30) {
                app.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                app
            }
            return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val service = PhoneA11yService.instance
        if (service != null) {
            runCatching {
                return service.overlayWindowContext() to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        }
        return null
    }

    /**
     * 2026-11-18：Gemini 在国内要先连代理（NekoBox）。代理没连上就先打开 NekoBox，让他点「连接」，
     * 连上（系统里出现 VPN）后自动接着打开 Gemini；90 秒还没连上就算了，只留一条提示。
     */
    fun startGeminiActivity(app: Context, topic: String = "", preferVoice: Boolean = false): Boolean {
        return when (val step = ProxyGate.before(app)) {
            ProxyGate.Step.Direct -> startGeminiDirect(app, topic, preferVoice)
            is ProxyGate.Step.OpenProxyFirst -> {
                ProxyGate.openProxyThen(app, step.pkg) { startGeminiDirect(app, topic, preferVoice) }
                true
            }
        }
    }

    private fun startGeminiDirect(app: Context, topic: String = "", preferVoice: Boolean = false): Boolean {
        val candidates = buildCandidateIntents(app, preferVoice)
        if (candidates.isEmpty()) {
            Log.w(TAG, "no Gemini or Google Search app installed")
            return false
        }

        // Priority 1: Live AccessibilityService context (official Android 10–15 BAL exemption).
        val a11y = PhoneA11yService.instance
        if (a11y != null) {
            for (intent in candidates) {
                val ok = runCatching { a11y.startActivity(intent) }.isSuccess
                if (ok) {
                    Log.i(TAG, "Gemini launched via PhoneA11yService: pkg=${intent.`package`} action=${intent.action}")
                    return true
                }
            }
        }

        // Priority 2: app.startActivity (with visible overlay window if attached) + high-priority fullScreenIntent fallback.
        var launched = false
        for (intent in candidates) {
            if (runCatching { app.startActivity(intent) }.isSuccess) {
                launched = true
                Log.i(TAG, "Gemini launched via Context.startActivity: pkg=${intent.`package`} action=${intent.action}")
                break
            }
        }
        val notifPosted = postFallbackNotification(app, topic, candidates.first(), fullScreen = true)
        return launched || notifPosted
    }

    private fun buildCandidateIntents(app: Context, preferVoice: Boolean): List<Intent> {
        val pm = app.packageManager
        val intents = ArrayList<Intent>(4)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        if (preferVoice) {
            for (pkg in GEMINI_PACKAGES) {
                val targetedVoice = Intent(Intent.ACTION_VOICE_COMMAND).setPackage(pkg).addFlags(flags)
                val resolved = runCatching { pm.resolveActivity(targetedVoice, 0) }.getOrNull()
                if (resolved != null) {
                    intents.add(targetedVoice)
                    break
                }
            }
            if (intents.isEmpty()) {
                val genericVoice = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(flags)
                val resolvedPkg = runCatching { pm.resolveActivity(genericVoice, 0)?.activityInfo?.packageName }.getOrNull()
                if (resolvedPkg in GEMINI_PACKAGES) {
                    intents.add(genericVoice)
                }
            }
        }

        for (pkg in GEMINI_PACKAGES) {
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            launch.addFlags(flags)
            intents.add(launch)
        }
        return intents
    }

    private fun postFallbackNotification(
        app: Context,
        topic: String,
        intent: Intent,
        fullScreen: Boolean,
    ): Boolean = runCatching {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return@runCatching false
        if (!nm.areNotificationsEnabled()) return@runCatching false
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "双姐妹 · Gemini 共脑", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "主动接通 Gemini Live 或共脑对话的直达唤醒通知"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val pi = PendingIntent.getActivity(
            app,
            NOTIF_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = if (topic.isBlank()) {
            "共脑简报已复制到剪贴板，点击直达 Gemini"
        } else {
            "$topic（简报已复制，点击直达 Gemini）"
        }
        val builder = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle("⚡ 双姐妹主动接通 Gemini 共脑")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(if (fullScreen) Notification.CATEGORY_CALL else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setTimeoutAfter(45_000L)
        if (fullScreen) {
            builder.setFullScreenIntent(pi, true)
        }
        nm.notify(NOTIF_TAG, NOTIF_ID, builder.build())
        true
    }.getOrDefault(false)

    private class CountdownCard(
        private val app: Context,
        private val ctx: Context,
        private val windowType: Int,
        private val topic: String,
        private val hasBrief: Boolean,
        private var remainingSeconds: Int,
        private val onEnterNow: () -> Unit,
        private val onDelay10m: () -> Unit,
    ) {
        private val wm = ctx.getSystemService(WindowManager::class.java)
        private val palette = OverlayPalette.of(ctx)
        private var root: View? = null
        private var badgeView: TextView? = null
        private var countdownView: TextView? = null
        private var finished = false

        private val ticker = object : Runnable {
            override fun run() {
                if (finished || root == null) return
                remainingSeconds -= 1
                if (remainingSeconds <= 0) {
                    finished = true
                    onEnterNow()
                    return
                }
                badgeView?.text = "${remainingSeconds}s"
                countdownView?.text = "将在 ${remainingSeconds}… 后自动切入 Gemini"
                main.postDelayed(this, 1_000L)
            }
        }

        fun show(): Boolean {
            val manager = wm ?: return false
            val view = buildView()
            val maxW = minOf(
                ctx.resources.displayMetrics.widthPixels - ctx.dp(32f),
                ctx.dp(348f),
            ).coerceAtLeast(ctx.dp(260f))
            val lp = WindowManager.LayoutParams(
                maxW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = ctx.dp(72f)
            }
            return try {
                manager.addView(view, lp)
                root = view
                main.postDelayed(ticker, 1_000L)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "countdown overlay add failed", t)
                false
            }
        }

        private fun buildView(): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = ctx.dp(16f)
                setPadding(pad, ctx.dp(14f), pad, ctx.dp(14f))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(18f).toFloat()
                    setColor(palette.fill)
                    setStroke(ctx.dp(1.5f).coerceAtLeast(1), palette.accent)
                }
                elevation = ctx.dp(8f).toFloat()
            }

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(ctx).apply {
                text = "⚡ 双姐妹主动接通 Gemini 共脑"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.accent)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val badge = TextView(ctx).apply {
                text = "${remainingSeconds}s"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                fontFeatureSettings = "tnum"
                setTextColor(palette.onTonal)
                setPadding(ctx.dp(8f), ctx.dp(3f), ctx.dp(8f), ctx.dp(3f))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(999f).toFloat()
                    setColor(palette.tonal)
                }
            }
            badgeView = badge
            header.addView(badge)
            card.addView(header)

            card.addView(TextView(ctx).apply {
                text = topic.ifBlank { "现在与 Gemini 同步一下当前进度与卡点" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.25f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ctx.dp(8f), 0, ctx.dp(6f))
            })

            if (hasBrief) {
                card.addView(TextView(ctx).apply {
                    text = "✓ 共脑简报已写入剪贴板，进入后可直接粘贴"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    setPadding(0, 0, 0, ctx.dp(4f))
                })
            }

            val countdown = TextView(ctx).apply {
                text = "将在 ${remainingSeconds}… 后自动切入 Gemini"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(palette.muted)
                fontFeatureSettings = "tnum"
                setPadding(0, 0, 0, ctx.dp(10f))
            }
            countdownView = countdown
            card.addView(countdown)

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            fun actionBtn(label: String, accent: Boolean, onTap: () -> Unit) = TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                if (accent) setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (accent) palette.onAccent else palette.ink)
                setPadding(ctx.dp(12f), ctx.dp(7f), ctx.dp(12f), ctx.dp(7f))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(12f).toFloat()
                    setColor(if (accent) palette.accent else palette.pressed)
                }
                isClickable = true
                setOnClickListener {
                    if (finished) return@setOnClickListener
                    finished = true
                    main.removeCallbacks(ticker)
                    onTap()
                }
            }

            btnRow.addView(
                actionBtn("正事延后 10m", accent = false, onTap = onDelay10m),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = ctx.dp(8f) },
            )
            btnRow.addView(actionBtn("立即进入", accent = true, onTap = onEnterNow))
            card.addView(btnRow)

            return card
        }

        fun destroy() {
            finished = true
            main.removeCallbacks(ticker)
            val v = root ?: return
            root = null
            runCatching { wm?.removeViewImmediate(v) }
        }
    }
}
