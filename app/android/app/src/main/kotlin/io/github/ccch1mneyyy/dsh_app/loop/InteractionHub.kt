package io.github.ccch1mneyyy.dsh_app.loop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings

/**
 * InteractionHub: the surfaces that can put a question in front of the person.
 *
 * v1 had one surface, the page. v2 (T3) adds the notification fallback: when the page cannot be
 * brought up — the common case on ROMs that block "show pop-up windows while running in the
 * background" — the same event is asked again as a heads-up notification on its own channel
 * ([CHANNEL_LOOP]), with 返回 and 划掉 as the two answers. The event stays *open* through the
 * fallback; whichever surface answers first wins, because both go through [LoopEngine.answer],
 * which is idempotent and records the surface it was closed from.
 *
 * The notification reuses the discipline of NotificationGate.showPrompt: a fresh random nonce in
 * the broadcast action, a receiver registered at runtime with RECEIVER_NOT_EXPORTED, and buttons
 * that only mean something for this one presentation. Nothing is read from the screen — the text is
 * the app name plus whatever the [AgentBridge] produced.
 */
object InteractionHub {
    internal const val TAG = "DshLoop"
    private const val SHOW_DEADLINE_MS = 3_000L
    internal const val EXTRA_EVENT = "loop_event_id"
    internal const val EXTRA_TIMEOUT = "loop_timeout_ms"
    internal const val EXTRA_PACKAGE = "loop_package"
    internal const val EXTRA_TEXT = "loop_text"

    /** Own channel, so muting these does not mute DSH's agent prompts. */
    internal const val CHANNEL_LOOP = "dsh-loop"

    private const val NOTIFICATION_TAG = "dsh-loop-prompt"
    private const val EXTRA_CHOICE = "loop_choice"
    private const val CHOICE_RETURN = "return"
    private const val CHOICE_DISMISS = "dismiss"

    /** How long the local bridge may take before the page falls back to its own sentence. */
    private const val AGENT_TIMEOUT_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    fun show(context: Context, engine: LoopEngine, show: LoopEngine.Outcome.Show) {
        val id = show.event.eventId
        val app = context.applicationContext
        val sentence = runBridge(show.event)
        val intent = Intent(app, InteractionActivity::class.java)
            .putExtra(EXTRA_EVENT, id)
            .putExtra(EXTRA_TIMEOUT, show.timeoutMs)
            .putExtra(EXTRA_PACKAGE, show.event.packageName)
            .putExtra(EXTRA_TEXT, sentence)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            app.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "start page", t)
            LoopRuntime.diagnostics.fault(LoopDiagnostics.KIND_SURFACE, t, "start page")
            // The start itself was refused: go straight to the fallback surface.
            notifyFallback(app, engine, show, sentence, reason = "start_failed")
            return
        }
        main.postDelayed({
            // Still TRIGGERED (never SHOWN) after the deadline: the start was swallowed.
            if (engine.stateOf(id) == EventState.TRIGGERED) {
                Log.i(TAG, "page not visible after ${SHOW_DEADLINE_MS}ms, falling back to notification: $id")
                notifyFallback(app, engine, show, sentence, reason = "not_shown")
            }
        }, SHOW_DEADLINE_MS)
    }

    /** The bridge's sentence, or null when there is none (v0 has no agent turn yet). */
    private fun runBridge(event: LoopEvent): String? =
        when (val d = DeliveryGuard.run(LoopRuntime.bridge(), event, AGENT_TIMEOUT_MS)) {
            is AgentBridge.Delivery.Reply -> d.text
            is AgentBridge.Delivery.Fallback -> {
                if (d.reason != "no_bridge") Log.i(TAG, "agent fallback for ${event.eventId}: ${d.reason}")
                null
            }
        }

    /**
     * Notification surface for an event whose page did not appear. The event is *not* closed here:
     * it is moved to `surface="notification"` and stays open until the person answers, swipes it
     * away (DISMISSED) or the deadline passes (TIMEOUT). When even the notification cannot be
     * posted, the receipt is the honest v1 answer, UNAVAILABLE(not_shown).
     */
    private fun notifyFallback(
        app: Context,
        engine: LoopEngine,
        show: LoopEngine.Outcome.Show,
        sentence: String?,
        reason: String,
    ) {
        val id = show.event.eventId
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm == null || !channelUsable(nm)) {
            Log.w(TAG, "no usable notification surface, closing as UNAVAILABLE: $id")
            LoopRuntime.diagnostics.fault(LoopDiagnostics.KIND_SURFACE, null, "$reason: notification unavailable")
            engine.answer(id, EventResult.UNAVAILABLE, reason = "${reason}_no_notification")
            return
        }
        // Record where the question actually went, and keep it open.
        engine.markSurface(id, SURFACE_NOTIFICATION)?.let { Log.i(TAG, "notification surface: $id") } ?: run {
            // Closed meanwhile (person answered, config changed): nothing to ask.
            Log.i(TAG, "event gone before the notification could be posted: $id")
            return
        }

        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val action = "${app.packageName}.DSH_LOOP.$id.$nonce"
        val label = appLabel(app, show.event.packageName)
        val body = sentence ?: AppStrings.get(app, R.string.loop_page_text, label)
        val nid = notificationId(id)
        val timeoutMs = show.timeoutMs.coerceIn(5_000, 10 * 60_000L)

        val intents = mutableListOf<PendingIntent>()
        var cleaned = false
        // Set once the receiver below exists: a local function cannot forward-reference a local val.
        var detach: (() -> Unit)? = null
        var expiry: Runnable? = null

        fun cleanup() {
            if (cleaned) return
            cleaned = true
            expiry?.let { main.removeCallbacks(it) }
            intents.forEach { runCatching { it.cancel() } }
            detach?.invoke()
            detach = null
            runCatching { nm.cancel(NOTIFICATION_TAG, nid) }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                when (intent.getStringExtra(EXTRA_CHOICE)) {
                    // First answer wins inside LoopEngine.answer; a late tap returns null and is
                    // ignored, so it cannot overwrite a real answer.
                    CHOICE_RETURN -> engine.answer(id, EventResult.RETURNED, surface = SURFACE_NOTIFICATION)
                    CHOICE_DISMISS -> engine.answer(id, EventResult.DISMISSED, surface = SURFACE_NOTIFICATION)
                }
                cleanup()
            }
        }
        detach = { runCatching { app.unregisterReceiver(receiver) } }
        // setTimeoutAfter only hides the notification; it does not answer. The deadline is ours.
        expiry = Runnable {
            engine.answer(id, EventResult.TIMEOUT, surface = SURFACE_NOTIFICATION)
            cleanup()
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, IntentFilter(action))
            fun broadcast(choice: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
                app, nid * 8 + code,
                Intent(action).setPackage(app.packageName).putExtra(EXTRA_CHOICE, choice),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            ).also { intents += it }

            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_LOOP)
            else @Suppress("DEPRECATION") Notification.Builder(app).setPriority(Notification.PRIORITY_HIGH)
            builder.setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(AppStrings.get(app, R.string.loop_page_title))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(false)
                .setDeleteIntent(broadcast(CHOICE_DISMISS, 1))
                .addAction(
                    Notification.Action.Builder(null, AppStrings.get(app, R.string.loop_page_return), broadcast(CHOICE_RETURN, 0)).build(),
                )
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(timeoutMs)
            nm.notify(NOTIFICATION_TAG, nid, builder.build())
            main.postDelayed(expiry!!, timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "notification fallback", t)
            LoopRuntime.diagnostics.fault(LoopDiagnostics.KIND_SURFACE, t, "notification fallback")
            cleanup()
            engine.answer(id, EventResult.UNAVAILABLE, reason = "${reason}_notification_failed")
        }
    }

    private fun notificationId(eventId: String): Int = eventId.hashCode() and 0x00FFFFFF

    private fun appLabel(context: Context, pkg: String?): String =
        pkg?.let { p -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() } ?: pkg.orEmpty()

    private fun channelUsable(nm: NotificationManager): Boolean = runCatching {
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        val c = nm.getNotificationChannel(CHANNEL_LOOP) ?: return false
        c.importance >= NotificationManager.IMPORTANCE_HIGH
    }.getOrDefault(false)

    /** Created on demand, the first time the loop is switched on. */
    internal fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_LOOP) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LOOP, AppStrings.get(context, R.string.loop_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = AppStrings.get(context, R.string.loop_channel_description)
                },
            )
        }
    }

    const val SURFACE_ACTIVITY = "activity"
    const val SURFACE_NOTIFICATION = "notification"
}
