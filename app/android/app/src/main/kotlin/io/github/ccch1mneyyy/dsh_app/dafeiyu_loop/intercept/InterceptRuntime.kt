package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.AtomicJsonFile
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.BeijingClock
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptConfig
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptDecision
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptDrill
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptEnv
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptGuard
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptLedger
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptLedgerBook
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptReceipt
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptRules
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Switches
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ArbiterShadow
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.LockSafety
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist
import java.io.File
import java.util.UUID

/**
 * Pure JVM-testable 2.5-minute (150s) local bout switch-out buffer (4.13).
 * Deduplicated per bout/seal scope key so each bout grants at most 1 temporary switch-out
 * without consuming daily emergency disarm quota.
 */
class BoutSwitchBuffer(private val durationMs: Long = BUFFER_DURATION_MS) {
    private val usedScopes = LinkedHashSet<String>()
    private var activeScopeKey: String? = null
    private var activeUntilElapsed: Long = 0L

    @Synchronized
    fun canTrigger(scopeKey: String, elapsedRealtime: Long): Boolean {
        val key = scopeKey.trim()
        if (key.isEmpty()) return false
        if (isActive(elapsedRealtime)) return false
        return key !in usedScopes
    }

    @Synchronized
    fun trigger(scopeKey: String, elapsedRealtime: Long): Boolean {
        val key = scopeKey.trim()
        if (!canTrigger(key, elapsedRealtime)) return false
        if (usedScopes.size >= MAX_SCOPES) {
            usedScopes.firstOrNull()?.let { usedScopes.remove(it) }
        }
        usedScopes.add(key)
        activeScopeKey = key
        activeUntilElapsed = elapsedRealtime + durationMs.coerceAtLeast(1_000L)
        return true
    }

    @Synchronized
    fun isActive(elapsedRealtime: Long): Boolean {
        val until = activeUntilElapsed
        if (until <= 0L) return false
        if (elapsedRealtime < until) return true
        activeUntilElapsed = 0L
        activeScopeKey = null
        return false
    }

    @Synchronized
    fun remainingSeconds(elapsedRealtime: Long): Int {
        if (!isActive(elapsedRealtime)) return 0
        return ((activeUntilElapsed - elapsedRealtime + 999L) / 1_000L).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun activeScope(): String? = activeScopeKey

    @Synchronized
    fun usedScopesSnapshot(): List<String> = usedScopes.toList()

    @Synchronized
    fun restoreUsedScopes(scopes: Collection<String>) {
        scopes.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_SCOPES).forEach {
            usedScopes.add(it)
        }
    }

    companion object {
        const val BUFFER_DURATION_MS = 150_000L
        private const val MAX_SCOPES = 32
    }
}

/**
 * Pure JVM-testable guard deciding whether releasing a companion lock may auto-reopen [pkg].
 * Prevents the B站 launch -> re-lock -> release -> re-launch infinite loop (1.4).
 */
object CompanionReleasePolicy {
    fun shouldReopenTarget(pkg: String?, hasOpenOtherLock: Boolean, neverLock: Boolean): Boolean =
        !pkg.isNullOrBlank() && !neverLock && !hasOpenOtherLock

    fun resolveHasOpenOtherLock(
        arbiterShouldLock: Boolean?,
        proactiveLocked: Boolean,
        focusLocked: Boolean,
        guardStrictLocked: Boolean,
    ): Boolean = if (arbiterShouldLock != null) {
        arbiterShouldLock || focusLocked
    } else {
        proactiveLocked || focusLocked || guardStrictLocked
    }
}

/**
 * Pure JVM-testable convergence router for [InterceptRuntime.onForeground]:
 * ensures safety/release gates always win, and when a fresh arbiter verdict exists,
 * it is the sole truth source (preventing legacy rules from blocking when arbiter allows).
 */
object InterceptConvergencePolicy {
    enum class ForegroundAction {
        UNIFIED_RELEASE,
        ARBITER_SHADOW,
        ARBITER_ALLOW,
        ARBITER_LOCK,
        LEGACY_FALLBACK,
    }

    fun decideForeground(
        disarmed: Boolean,
        neverLock: Boolean,
        unifiedRelease: Boolean,
        boutBufferActive: Boolean,
        arbiterShouldLock: Boolean?,
        arbiterReasonTag: String?,
    ): ForegroundAction {
        if (disarmed || neverLock || unifiedRelease || boutBufferActive) {
            return ForegroundAction.UNIFIED_RELEASE
        }
        if (arbiterShouldLock != null) {
            if (arbiterReasonTag == "shadow") return ForegroundAction.ARBITER_SHADOW
            return if (arbiterShouldLock) ForegroundAction.ARBITER_LOCK else ForegroundAction.ARBITER_ALLOW
        }
        return ForegroundAction.LEGACY_FALLBACK
    }
}

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
    private const val BUFFER_PREFS = "dsh_bout_buffer"
    private const val KEY_USED_SCOPES = "used_scopes"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var guard: InterceptGuard? = null
    private var ledger: InterceptLedgerBook? = null
    private val boutBuffer = BoutSwitchBuffer()

    /** The page must not stay up forever; the kernel keeps the receipt either way. */
    private const val PAGE_TTL_MS = 10 * 60_000L

    fun ensure(context: Context): InterceptGuard {
        guard?.let { return it }
        app = context.applicationContext
        runCatching {
            val savedScopes = app.getSharedPreferences(BUFFER_PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_USED_SCOPES, emptySet()).orEmpty()
            boutBuffer.restoreUsedScopes(savedScopes)
        }
        val dir = File(File(app.filesDir, "loop"), "intercept")
        val book = InterceptLedgerBook(AtomicJsonFile(File(dir, "ledger.json")))
        book.recover()
        val g = InterceptGuard(
            AtomicJsonFile(File(dir, "guard.json")), book,
            clock = System::currentTimeMillis,
            ids = { UUID.randomUUID().toString() },
        ).withStrictLink {
            FocusRuntime.ensure(app)
            FocusRuntime.isActive() || runCatching {
                io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime.isStrictSession()
            }.getOrDefault(false)
        }
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
    fun effectiveLockMode(): String {
        val g = guard
        if (g != null) return g.effectiveLockMode()
        val configured = InterceptRules.normalizeLockMode(config().lockMode)
        if (configured == InterceptRules.MODE_STRICT) return InterceptRules.MODE_STRICT
        if (configured == InterceptRules.MODE_LINKED) {
            val strictSession = runCatching {
                io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime.isStrictSession()
            }.getOrDefault(false)
            if (strictSession) return InterceptRules.MODE_STRICT
        }
        return InterceptRules.MODE_TRAINING
    }
    fun activeReleasesCount(): Int = guard?.activeReleasesCount() ?: 0

    fun addWhitelist(packages: Collection<String>): Int {
        val count = guard?.addWhitelist(packages) ?: 0
        if (count > 0) LoopRuntime.configChanged()
        return count
    }

    fun removeWhitelist(packageName: String): Boolean {
        val ok = guard?.removeWhitelist(packageName) == true
        if (ok) LoopRuntime.configChanged()
        return ok
    }

    fun clearWhitelist(): Int {
        val count = guard?.clearWhitelist() ?: 0
        if (count > 0) LoopRuntime.configChanged()
        return count
    }

    fun restoreLockNow(): Boolean {
        val ok = guard?.restoreLockNow() == true
        if (ok) LoopRuntime.configChanged()
        return ok
    }

    fun setConfig(context: Context, next: InterceptConfig) {
        ensure(context).setConfig(next, System.currentTimeMillis())
        LoopRuntime.configChanged()
    }

    fun canTriggerBoutBuffer(scopeKey: String, elapsedRealtime: Long = SystemClock.elapsedRealtime()): Boolean =
        boutBuffer.canTrigger(scopeKey, elapsedRealtime)

    fun activateBoutBuffer(scopeKey: String, elapsedRealtime: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!boutBuffer.trigger(scopeKey, elapsedRealtime)) return false
        if (::app.isInitialized) {
            runCatching {
                app.getSharedPreferences(BUFFER_PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_USED_SCOPES, boutBuffer.usedScopesSnapshot().toSet())
                    .apply()
            }
        }
        runCatching { ProactiveRuntime.onBoutBufferActivated(BoutSwitchBuffer.BUFFER_DURATION_MS) }
        Log.i(TAG, "bout buffer activated for scope=$scopeKey (${BoutSwitchBuffer.BUFFER_DURATION_MS / 1000}s)")
        return true
    }

    fun isBoutBufferActive(elapsedRealtime: Long = SystemClock.elapsedRealtime()): Boolean =
        boutBuffer.isActive(elapsedRealtime)

    fun boutBufferRemainingSeconds(elapsedRealtime: Long = SystemClock.elapsedRealtime()): Int =
        boutBuffer.remainingSeconds(elapsedRealtime)

    fun hasOpenCompanionLock(): Boolean = guardOrNull()?.openByCompanion() != null

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
        // 裁决器影子对比：只记新旧分歧，不执法。
        runCatching { ArbiterShadow.observe(app, packageName) }
        // 2026-11-18 锁机安全：紧急解除期间什么都不锁；桌面 / DSH 自己 / 系统界面永远不锁。
        val disarmed = LockSafety.disarmed(app, now)
        val neverLock = LockSafety.neverLock(app, packageName)
        // 统一放行（刷题达标 / 花余额放行 / 急事放行 / 破窗 / 2.5 分钟局内切出缓冲）优先级高于所有拦截。
        val unifiedRelease = runCatching { ProactiveRuntime.hasUnifiedRelease(now) }.getOrDefault(false)
        val boutBufferActive = isBoutBufferActive()
        // H5：arbiterEnforce=true 且已有新鲜裁决器输出时，只认 ArbiterDecision.decide 执法。
        val arbiterOutcome = if (disarmed || neverLock || unifiedRelease || boutBufferActive) {
            null
        } else {
            runCatching { ArbiterShadow.evaluateForEnforce(app, packageName) }.getOrNull()
        }
        when (InterceptConvergencePolicy.decideForeground(
            disarmed = disarmed,
            neverLock = neverLock,
            unifiedRelease = unifiedRelease,
            boutBufferActive = boutBufferActive,
            arbiterShouldLock = arbiterOutcome?.shouldLock,
            arbiterReasonTag = arbiterOutcome?.reasonTag,
        )) {
            InterceptConvergencePolicy.ForegroundAction.UNIFIED_RELEASE -> return
            InterceptConvergencePolicy.ForegroundAction.ARBITER_SHADOW -> {
                LockSafety.shadowHit(app, packageName, arbiterOutcome!!.why, "arbiter")
                return
            }
            InterceptConvergencePolicy.ForegroundAction.ARBITER_ALLOW -> {
                clearOpenIfPackageMatches(packageName, "arbiter_open", now)
                return
            }
            InterceptConvergencePolicy.ForegroundAction.ARBITER_LOCK -> {
                val out = arbiterOutcome!!
                if (resurface(packageName, out.why, now, out.unlockHow, arbiterShouldLock = true)) return
                if (screenBusy()) {
                    Log.i(TAG, "surface busy (" + VisibleSurface.holder() + "); no arbiter lock for " + packageName)
                    return
                }
                val d = g.force(packageName, null, env(now), now, strict = true)
                if (d is InterceptDecision.Block) {
                    show(d, now, out.why, out.unlockHow)
                }
                return
            }
            InterceptConvergencePolicy.ForegroundAction.LEGACY_FALLBACK -> {
                // 裁决器关闭或决策过期（>10 分钟）时安全回退：仅恢复仍未结束的锁页。
                if (resurface(packageName, null, now)) return
            }
        }
        // 2026-11-19 整理：「娱乐锁机」自己的名单 / 冷却 / 每日上限 / 开关不再自己判锁——
        // 它的名单已并入插件的 App 分类表（娱乐 / 游戏），锁不锁由插件按分类 + 余额 + 局判，
        // 经 ProactiveRuntime → [forceBlock] 落到同一张锁页。两套规则同时判，会出现「插件放行、这里又拦」。
    }

    /**
     * 大肥鱼说"锁"（companion force ≥ 3）: lock [packageName] through the same page and the same
     * book, whatever this feature's own switch and caps say (see [InterceptGuard.force]). False when
     * nothing was shown; the companion asks again within a minute, so a busy screen only delays it.
     */
    fun forceBlock(
        context: Context,
        packageName: String,
        label: String?,
        why: String? = null,
        strict: Boolean = false,
        unlockHow: String? = null,
    ): Boolean {
        val g = ensure(context)
        val now = System.currentTimeMillis()
        expireStale(now)
        if (LockSafety.disarmed(app, now) || LockSafety.neverLock(app, packageName)) {
            Log.i(TAG, "companion lock refused for " + packageName + " (disarmed or never-lock)")
            return false
        }
        if (runCatching { ProactiveRuntime.hasUnifiedRelease(now) }.getOrDefault(false) || isBoutBufferActive()) {
            Log.i(TAG, "companion lock skipped for " + packageName + " (unified release or bout buffer active)")
            return false
        }
        val arbiterOutcome = runCatching { ArbiterShadow.evaluateForEnforce(app, packageName) }.getOrNull()
        if (arbiterOutcome != null && !arbiterOutcome.shouldLock) {
            if (arbiterOutcome.reasonTag != "shadow") {
                clearOpenIfPackageMatches(packageName, "arbiter_open", now)
            }
            Log.i(TAG, "companion lock skipped for $packageName (arbiter=${arbiterOutcome.reasonTag})")
            return false
        }
        val effectiveWhy = if (arbiterOutcome != null) {
            why?.takeIf { it.isNotBlank() } ?: arbiterOutcome.why
        } else why
        val effectiveUnlockHow = if (arbiterOutcome != null) {
            unlockHow?.takeIf { it.isNotBlank() } ?: arbiterOutcome.unlockHow
        } else unlockHow
        val effectiveStrict = if (arbiterOutcome != null) true else strict
        // A lock already in force: show it again if its page is not on screen (or let a superseded
        // one go and decide this app afresh).
        if (resurface(packageName, effectiveWhy, now, effectiveUnlockHow, arbiterShouldLock = arbiterOutcome?.shouldLock)) return true
        if (screenBusy()) return false
        val d = g.force(packageName, label, env(now), now, effectiveStrict)
        if (d !is InterceptDecision.Block) {
            Log.d(TAG, "companion lock skipped for " + packageName + ": " + ((d as? InterceptDecision.Allow)?.reason ?: "ignore"))
            return false
        }
        show(d, now, effectiveWhy, effectiveUnlockHow)
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
        if (pendingLaunchEventId == id) pendingLaunchEventId = null
        val arbiterOutcome = pkg?.let {
            runCatching { ArbiterShadow.evaluateForEnforce(context, it) }.getOrNull()
        }
        val arbiterLocked = arbiterOutcome?.shouldLock == true
        val proactiveLocked = pkg != null && runCatching {
            ProactiveRuntime.wouldLock(pkg)
        }.getOrDefault(false)
        val focusLocked = pkg != null && runCatching {
            FocusRuntime.isActive() &&
                pkg !in FocusRuntime.state().allowed &&
                !SystemSafeAllowlist.isSelfPackage(pkg, context.packageName)
        }.getOrDefault(false)
        val guardStrictLocked = pkg != null && g.config.targetFor(pkg) != null &&
            g.effectiveLockMode() == InterceptRules.MODE_STRICT &&
            !runCatching { ProactiveRuntime.hasUnifiedRelease(now) }.getOrDefault(false) &&
            !isBoutBufferActive()
        val hasOpenOtherLock = CompanionReleasePolicy.resolveHasOpenOtherLock(
            arbiterShouldLock = arbiterOutcome?.shouldLock,
            proactiveLocked = proactiveLocked,
            focusLocked = focusLocked,
            guardStrictLocked = guardStrictLocked,
        )
        val neverLock = pkg != null && runCatching { LockSafety.neverLock(context, pkg) }.getOrDefault(false)
        if (CompanionReleasePolicy.shouldReopenTarget(pkg, hasOpenOtherLock, neverLock)) {
            runCatching { openTarget(context, pkg!!) }
            Log.i(TAG, "companion force dropped: released and reopened $pkg ($id)")
        } else {
            Log.i(
                TAG,
                "companion force dropped: closed $pkg ($id), skip reopen " +
                    "(arbiter=$arbiterLocked proactive=$proactiveLocked focus=$focusLocked guardStrict=$guardStrictLocked neverLock=$neverLock)",
            )
        }
        return true
    }

    /** What the open lock's page showed, so it can be shown again after 灭屏 / 切走. */
    private var openLabel: String? = null
    private var openWhy: String? = null
    private var openUnlockHow: String? = null
    private var pendingLaunchEventId: String? = null
    private var pendingLaunchAtElapsed: Long = 0L

    /** A lock nobody resolved in this long is stale (its page is long gone). */
    private const val STALE_OPEN_MS = 30 * 60_000L

    private fun expireStale(now: Long) {
        val id = guard?.expireStale(now, STALE_OPEN_MS) ?: return
        InterceptPageActivity.withdraw(id)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, id)
        if (pendingLaunchEventId == id) pendingLaunchEventId = null
        Log.i(TAG, "stale lock closed: " + id)
    }

    private fun clearOpenIfPackageMatches(packageName: String, reason: String, now: Long) {
        val g = guard ?: return
        val open = g.openEventId() ?: return
        if (g.openPackage() != packageName) return
        InterceptPageActivity.withdraw(open)
        g.onDismissed(open, reason, now)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, open)
        if (pendingLaunchEventId == open) pendingLaunchEventId = null
        Log.i(TAG, "stale lock cleared for $packageName ($open, $reason)")
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
    private fun resurface(
        packageName: String,
        why: String?,
        now: Long,
        unlockHow: String? = null,
        arbiterShouldLock: Boolean? = null,
    ): Boolean {
        val g = guard ?: return false
        val open = g.openEventId() ?: return false
        if (InterceptPageActivity.isOnScreen(open) ||
            (pendingLaunchEventId == open && SystemClock.elapsedRealtime() - pendingLaunchAtElapsed < 1_500L)
        ) return true
        val pkg = g.openPackage()
        if (pkg == packageName) {
            if (screenBusy()) return true
            if (LockSafety.shadowHit(app, packageName, why ?: openWhy, "resurface")) return true
            if (!takeScreen(open)) return true
            if (!why.isNullOrBlank()) openWhy = why
            if (!unlockHow.isNullOrBlank()) openUnlockHow = unlockHow
            val label = openLabel ?: g.config.targetFor(packageName)?.label ?: packageName
            val ok = InterceptPageActivity.start(
                app, open, packageName, label, config().trainingMs, now + PAGE_TTL_MS, why ?: openWhy,
                lockMode = effectiveLockMode(), openSinceWall = g.state.openSinceWall, todayCount = g.state.countToday,
                unlockHow = unlockHow ?: openUnlockHow,
            )
            if (ok) {
                pendingLaunchEventId = open
                pendingLaunchAtElapsed = SystemClock.elapsedRealtime()
            } else {
                VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, open)
            }
            Log.i(TAG, "lock resurfaced for " + packageName + " (" + open + ") ok=" + ok)
            return true
        }
        val lockable = arbiterShouldLock ?: (
            g.config.targetFor(packageName) != null ||
                runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime.wouldLock(packageName) }.getOrDefault(false)
            )
        if (!lockable) return true
        InterceptPageActivity.withdraw(open)
        g.onDismissed(open, "superseded", now)
        VisibleSurface.release(VisibleSurface.OWNER_INTERCEPT, open)
        if (pendingLaunchEventId == open) pendingLaunchEventId = null
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
            runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveOverlay.yieldForLock() }
                .onFailure { Log.w(TAG, "overlay yield", it) }
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
        }
        return VisibleSurface.claim(VisibleSurface.OWNER_INTERCEPT, eventId)
    }

    private fun show(block: InterceptDecision.Block, now: Long, why: String? = null, unlockHow: String? = null) {
        // 测试模式：决定照记，页面不弹——记成 NOT_SHOWN，免得占着一把「开着的锁」。
        if (LockSafety.shadowHit(app, block.target.packageName, why, "intercept")) {
            guard?.onUnavailable(block.eventId, "shadow", System.currentTimeMillis())
            return
        }
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
        openUnlockHow = unlockHow
        val ok = InterceptPageActivity.start(
            app, block.eventId, block.target.packageName, block.target.label,
            config().trainingMs, now + PAGE_TTL_MS, why,
            lockMode = effectiveLockMode(), openSinceWall = now, todayCount = guard?.state?.countToday ?: 1,
            unlockHow = unlockHow,
        )
        if (ok) {
            pendingLaunchEventId = block.eventId
            pendingLaunchAtElapsed = SystemClock.elapsedRealtime()
        } else {
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
        if (eventId == null || pendingLaunchEventId == eventId) pendingLaunchEventId = null
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
        runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime.watchDefiance(source) }
    }

    /** Put a released app back in front, so 放行 actually means "you may go". */
    fun openTarget(context: Context, packageName: String) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "open " + packageName, it) }
    }

    fun receipts(limit: Int = 50): List<InterceptReceipt> = guard?.receipts(limit) ?: emptyList()

    fun clearReceipts(before: Long): Int = guard?.clearReceipts(before) ?: 0

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
