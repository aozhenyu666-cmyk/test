package io.github.ccch1mneyyy.dsh_app.loop.intercept

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.loop.core.AtomicJsonFile
import io.github.ccch1mneyyy.dsh_app.loop.core.BeijingClock
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptConfig
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptDecision
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptDrill
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptEnv
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptGuard
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptLedger
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptLedgerBook
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptReceipt
import io.github.ccch1mneyyy.dsh_app.loop.core.Switches
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import java.io.File
import java.util.UUID

/**
 * Android adapter for 娱乐 APP 拦截 (loop/core/Intercept.kt). Owns one [InterceptGuard] and one
 * 行为拦截账 [InterceptLedgerBook] per process, decides from the verified foreground package that
 * [LoopRuntime] reads back from the window list, and shows exactly one blocking page.
 *
 * It has **no scheduler, no alarm and no timer of its own**: an interception is decided when the
 * person opens a target app, which is precisely when the accessibility service already reports a
 * window change. The proactive runtime is deliberately not involved — the two features have
 * separate books and neither can pause the other.
 *
 * Main thread only (accessibility events and pages both live there).
 */
object InterceptRuntime {
    private const val TAG = "DshIntercept"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var guard: InterceptGuard? = null
    private var ledger: InterceptLedgerBook? = null

    /** The page must not stay up forever; the kernel keeps the receipt either way. */
    private const val PAGE_TTL_MS = 10 * 60_000L

    fun ensure(context: Context): InterceptGuard {
        guard?.let { return it }
        app = context.applicationContext
        val dir = File(File(app.filesDir, "loop"), "intercept")
        val book = InterceptLedgerBook(AtomicJsonFile(File(dir, "ledger.json")))
        book.recover()
        val g = InterceptGuard(
            AtomicJsonFile(File(dir, "guard.json")), book,
            clock = System::currentTimeMillis, ids = { UUID.randomUUID().toString() },
        )
        g.recover(System.currentTimeMillis())
        ledger = book
        guard = g
        Log.i(TAG, "ready: targets=" + g.config.targets.size + " enabled=" + g.config.enabled)
        return g
    }

    /** The three ways a page can grant passage, re-exported so the page imports one class only. */
    val RELEASE_TRAINING = InterceptGuard.RELEASE_TRAINING
    val RELEASE_RULE = InterceptGuard.RELEASE_RULE
    val RELEASE_WHITELIST = InterceptGuard.RELEASE_WHITELIST

    fun guardOrNull(): InterceptGuard? = guard
    fun ledger(): InterceptLedger = ledger?.state ?: InterceptLedger()
    fun config(): InterceptConfig = guard?.config ?: InterceptConfig()
    fun releaseMs(): Long = config().releaseMs

    fun setConfig(context: Context, next: InterceptConfig) {
        ensure(context).setConfig(next, System.currentTimeMillis())
        LoopRuntime.configChanged()
    }

    /** Whether the feature is on at all; part of [Switches] so the two features share one arbitration. */
    fun enabled(): Boolean = guard?.config?.enabled == true

    /**
     * One verified foreground reading, called from [LoopRuntime] right after the window list was
     * read back. Null / blank means "unreadable": nothing is decided and nothing is recorded.
     */
    fun onForeground(packageName: String?) {
        val g = guard ?: return
        if (packageName.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        expireStale(now)
        // A lock that is still in force comes back whenever its app does (亮屏 / 回到游戏), even
        // with the person's own interception switch off: the lock is state, the page is only its face.
        if (resurface(packageName, null, now)) return
        if (!enabled()) return
        // One visible DSH surface at a time. Checked *before* asking the guard, because a Block
        // decision immediately spends the cooldown and the daily count ([InterceptGuard.onForeground]
        // stamps lastTriggerAt and increments countToday). Refusing afterwards would charge the
        // person for an interception that never reached the screen, and the next attempt would be
        // swallowed by the cooldown. So: while someone else holds the screen, decide nothing at all.
        // A chat overlay does not count as "someone else": enforcement goes first (see [takeScreen]).
        if (screenBusy()) {
            Log.i(TAG, "surface busy (" + VisibleSurface.holder() + "); no decision for " + packageName)
            return
        }
        when (val d = g.onForeground(packageName, env(now), now)) {
            is InterceptDecision.Block -> show(d, now)
            is InterceptDecision.Allow -> Log.d(TAG, "allow " + packageName + ": " + d.reason)
            InterceptDecision.Ignore -> Unit
        }
    }

    /**
     * 大肥鱼说"锁"（companion force ≥ 3）: lock [packageName] through the same page and the same
     * book, whatever this feature's own switch and caps say (see [InterceptGuard.force]). False when
     * nothing was shown; the companion asks again within a minute, so a busy screen only delays it.
     */
    fun forceBlock(context: Context, packageName: String, label: String?, why: String? = null, strict: Boolean = false): Boolean {
        val g = ensure(context)
        val now = System.currentTimeMillis()
        expireStale(now)
        // A lock already in force: show it again if its page is not on screen (or let a superseded
        // one go and decide this app afresh).
        if (resurface(packageName, why, now)) return true
        if (screenBusy()) return false
        val d = g.force(packageName, label, env(now), now, strict)
        if (d !is InterceptDecision.Block) {
            Log.d(TAG, "companion lock skipped for " + packageName + ": " + ((d as? InterceptDecision.Allow)?.reason ?: "ignore"))
            return false
        }
        show(d, now, why)
        return true
    }

    /**
     * 讲通了: the companion dropped force below 3. Release only the block its own force opened,
     * never one the person's interception rule made, and put the app back in front.
     */
    fun releaseCompanion(context: Context): Boolean {
        val g = guardOrNull() ?: return false
        val id = g.openByCompanion() ?: return false
        val pkg = g.openPackage()
        val now = System.currentTimeMillis()
        if (!g.onReleased(id, RELEASE_RULE, now)) return false
        InterceptPageActivity.withdraw(id)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, id)
        if (pkg != null) runCatching { openTarget(context, pkg) }
        Log.i(TAG, "companion force dropped: released " + pkg + " (" + id + ")")
        return true
    }

    /** What the open lock's page showed, so it can be shown again after 灭屏 / 切走. */
    private var openLabel: String? = null
    private var openWhy: String? = null

    /** A lock nobody resolved in this long is stale (its page is long gone). */
    private const val STALE_OPEN_MS = 30 * 60_000L

    private fun expireStale(now: Long) {
        val id = guard?.expireStale(now, STALE_OPEN_MS) ?: return
        InterceptPageActivity.withdraw(id)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, id)
        Log.i(TAG, "stale lock closed: " + id)
    }

    /**
     * C1：锁状态和界面生命周期分开。An open lock lives in the guard; its page may have been stopped
     * by 灭屏, pushed behind by the launcher, or destroyed by the system. True when the open lock
     * handled this reading (nothing else may be decided for it).
     *
     *  * its page is on screen → nothing to do;
     *  * [packageName] is the locked app → show the same lock again (same eventId, same drill);
     *  * another lockable app while the page is not on screen → the old lock is superseded and
     *    ended, so the new app can be decided normally (false);
     *  * anything else (launcher, DSH, phone) → the lock stays, nothing to show.
     */
    private fun resurface(packageName: String, why: String?, now: Long): Boolean {
        val g = guard ?: return false
        val open = g.openEventId() ?: return false
        if (InterceptPageActivity.isOnScreen(open)) return true
        val pkg = g.openPackage()
        if (pkg == packageName) {
            if (screenBusy()) return true
            if (!takeScreen(open)) return true
            val label = openLabel ?: g.config.targetFor(packageName)?.label ?: packageName
            val ok = InterceptPageActivity.start(
                app, open, packageName, label, config().trainingMs, now + PAGE_TTL_MS, why ?: openWhy,
            )
            if (!ok) VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, open)
            Log.i(TAG, "lock resurfaced for " + packageName + " (" + open + ") ok=" + ok)
            return true
        }
        val lockable = g.config.targetFor(packageName) != null ||
            runCatching { io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime.wouldLock(packageName) }.getOrDefault(false)
        if (!lockable) return true
        InterceptPageActivity.withdraw(open)
        g.onDismissed(open, "superseded", now)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, open)
        return false
    }

    /**
     * Someone the lock may not push aside holds the screen (the native focus page). A chat overlay
     * is not such a holder: 执法类界面在 SurfaceSlot 里优先于普通浮层.
     */
    private fun screenBusy(): Boolean {
        val h = VisibleSurface.holder() ?: return false
        return h != VisibleSurface.OWNER_INTERCEPT && h != VisibleSurface.OWNER_PROACTIVE
    }

    /**
     * Claim the screen for a lock. A proactive overlay that still holds the slot (he chatted in it,
     * or it is waiting for an answer) yields: it folds into its bubble, which never takes focus, so
     * the lock page can prove it is on screen. The conversation is kept.
     */
    private fun takeScreen(eventId: String): Boolean {
        if (VisibleSurface.isHeldBy(VisibleSurface.OWNER_PROACTIVE)) {
            runCatching { io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveOverlay.yieldForLock() }
                .onFailure { Log.w(TAG, "overlay yield", it) }
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
        }
        return VisibleSurface.claim(VisibleSurface.OWNER_INTERCEPT, eventId)
    }

    private fun show(block: InterceptDecision.Block, now: Long, why: String? = null) {
        // Race guard: onForeground already refused to decide while another feature held the screen,
        // so reaching here with the slot taken means a page claimed it in between. The decision has
        // been made by then, so the honest record is NOT_SHOWN rather than pretending the block happened.
        if (!takeScreen(block.eventId)) {
            guard?.onUnavailable(block.eventId, "surface_busy", System.currentTimeMillis())
            Log.i(TAG, "surface busy (" + VisibleSurface.holder() + "); " + block.target.packageName + " passes this time")
            return
        }
        openLabel = block.target.label
        openWhy = why
        val ok = InterceptPageActivity.start(
            app, block.eventId, block.target.packageName, block.target.label,
            config().trainingMs, now + PAGE_TTL_MS, why,
        )
        if (!ok) {
            VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, block.eventId)
            // The system refused the page: the interception did not happen, so nothing is owed.
            guard?.onUnavailable(block.eventId, "start_failed", System.currentTimeMillis())
            Log.w(TAG, "could not show the block page for " + block.target.packageName)
        }
    }

    /** The page proved it is really on screen (attached + shown + laid out + focused). */
    fun onPageShown(eventId: String): Boolean = guard?.onPageShown(eventId, System.currentTimeMillis()) == true

    /**
     * The 训练题 this interception must show, or null when nothing is open.
     *
     * Minted by the guard from the eventId, so the page can only render it: it cannot invent,
     * reroll or shorten it. Rendering it is the page's job; judging it is the guard's.
     */
    fun drill(eventId: String): InterceptDrill? =
        guard?.drill()?.takeIf { guard?.openEventId() == eventId }

    /**
     * One press of the 训练题. False when this page names an interception that is not the open one,
     * so a stale page cannot drill its way into a pass for somebody else's interception.
     */
    fun tapDrill(eventId: String, value: Int): Boolean =
        guard?.tapDrill(eventId, value) == true

    /**
     * The page is gone from the screen (stopped, finished or destroyed). Freeing the shared slot
     * here - not in the guard - keeps "who owns the screen" a property of the adapter. The lock
     * itself is *not* ended here: 灭屏不清锁. It ends only through one of the reports below.
     */
    fun onPageGone(eventId: String?) {
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, eventId)
    }

    /** 训练通过 / 授权规则 / 白名单. True when the guard accepted it. */
    fun onReleased(eventId: String, how: String): Boolean {
        val ok = guard?.onReleased(eventId, how, System.currentTimeMillis()) == true
        // A pass was granted: the defiance check skips the app while the pass runs.
        if (ok) watchDefiance("lock-release")
        return ok
    }

    fun onExited(eventId: String): Boolean {
        val ok = guard?.onExited(eventId, System.currentTimeMillis()) == true
        // Left the lock page by its exit: if he is back in the game within the window, that is defiance.
        if (ok) watchDefiance("lock-exit")
        return ok
    }

    /**
     * Every other way the page closes (急事直达, 回 Gemini, 破窗, destroyed by the system): end the
     * lock record so the next lock can happen, and look again for defiance like any other close.
     */
    fun onDismissed(eventId: String, reason: String): Boolean {
        val ok = guard?.onDismissed(eventId, reason, System.currentTimeMillis()) == true
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, eventId)
        if (ok) watchDefiance("lock-" + reason.substringBefore(':'))
        return ok
    }

    fun onPaused(eventId: String?): Boolean {
        val ok = guard?.onPaused(eventId, InterceptGuard.DEFAULT_PAUSE_MS, System.currentTimeMillis()) == true
        if (ok && eventId != null) watchDefiance("lock-pause")
        return ok
    }

    /** True while [packageName] has an earned pass from this feature (训练 / 授权 / 白名单). */
    fun hasPass(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        val g = guard ?: return false
        if (packageName in g.config.whitelist) return true
        return (g.state.releases[packageName] ?: 0L) > now
    }

    private fun watchDefiance(source: String) {
        runCatching { io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime.watchDefiance(source) }
    }

    /** Put a released app back in front, so 放行 actually means "you may go". */
    fun openTarget(context: Context, packageName: String) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "open " + packageName, it) }
    }

    fun receipts(limit: Int = 50): List<InterceptReceipt> = guard?.receipts(limit) ?: emptyList()

    /** Facts read just before deciding, the same shape the proactive Gate has. */
    private fun env(now: Long): InterceptEnv {
        val core = PhoneControl.peek()
        val power = app.getSystemService(android.os.PowerManager::class.java)
        val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
        return InterceptEnv(
            screenOn = power?.isInteractive != false,
            unlocked = keyguard?.isKeyguardLocked != true,
            dshBusy = core != null && (core.tasks.isActive || core.tasks.callsInFlight > 0 || core.prompts.busy()),
            minuteOfDay = minuteOfDay(now),
            dayKey = dayOf(now),
        )
    }

    private fun minuteOfDay(wall: Long): Int = BeijingClock.minuteOfDay(wall)

    private fun dayOf(wall: Long): Long = BeijingClock.dayOf(wall)
}
