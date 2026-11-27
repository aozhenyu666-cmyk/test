package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.InterceptRuntime
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFactsOf
import java.io.File

/**
 * Pure JVM-testable enforcement evaluator when `arbiterEnforce` is enabled.
 * Guarantees I1 ([neverLock] / [isSystem] always open), I4 ([disarmed] always open),
 * test-mode shadow precedence, and I2 (non-empty [why] & [unlockHow] whenever [shouldLock] is true).
 */
data class ArbiterEnforceOutcome(
    val shouldLock: Boolean,
    val why: String,
    val unlockHow: String,
    val needPass: Boolean = false,
    val priority: String = "",
    val reasonTag: String = "",
)

object ArbiterEnforcePolicy {
    const val DEFAULT_ENFORCE_ENABLED = true

    fun resolveEnforceSwitch(persistedValue: Boolean?): Boolean = persistedValue ?: DEFAULT_ENFORCE_ENABLED

    fun evaluateAt(
        arbiterEnforce: Boolean,
        decision: ArbiterDecision?,
        receivedAt: Long,
        now: Long,
        pkg: String?,
        neverLock: Boolean,
        disarmed: Boolean,
        shadow: Boolean,
        isSystem: Boolean = false,
        released: Boolean = false,
    ): ArbiterEnforceOutcome? = evaluate(
        arbiterEnforce = arbiterEnforce,
        decision = decision,
        pkg = pkg,
        neverLock = neverLock,
        disarmed = disarmed,
        shadow = shadow,
        isSystem = isSystem,
        released = released,
        decisionFresh = decision != null && ArbiterDecision.isFresh(receivedAt, now),
    )

    fun evaluate(
        arbiterEnforce: Boolean,
        decision: ArbiterDecision?,
        pkg: String?,
        neverLock: Boolean,
        disarmed: Boolean,
        shadow: Boolean,
        isSystem: Boolean = false,
        released: Boolean = false,
        decisionFresh: Boolean = true,
    ): ArbiterEnforceOutcome? {
        if (!arbiterEnforce || decision == null || !decisionFresh) return null
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty() || neverLock || isSystem) {
            return ArbiterEnforceOutcome(shouldLock = false, why = "base", unlockHow = "", reasonTag = "never_lock")
        }
        if (disarmed) {
            return ArbiterEnforceOutcome(shouldLock = false, why = "disarmed", unlockHow = "", reasonTag = "disarmed")
        }
        if (released) {
            return ArbiterEnforceOutcome(shouldLock = false, why = "released", unlockHow = "", reasonTag = "released")
        }
        val verdict = decision.decide(p) { false }
        if (!verdict.lock) {
            return ArbiterEnforceOutcome(
                shouldLock = false,
                why = verdict.why,
                unlockHow = decision.unlockHow,
                needPass = false,
                priority = decision.priority,
                reasonTag = "arbiter_open",
            )
        }
        if (shadow) {
            return ArbiterEnforceOutcome(
                shouldLock = false,
                why = verdict.why.ifBlank { decision.why },
                unlockHow = decision.unlockHow,
                needPass = verdict.needPass,
                priority = decision.priority,
                reasonTag = "shadow",
            )
        }
        val effectiveWhy = verdict.why.ifBlank { decision.why }.ifBlank { "当前处于专注保护" }
        val effectiveUnlock = decision.unlockHow.ifBlank {
            if (verdict.needPass) "先跟鲸澜申请通行证" else "完成当前局或刷题解锁"
        }
        return ArbiterEnforceOutcome(
            shouldLock = true,
            why = effectiveWhy,
            unlockHow = effectiveUnlock,
            needPass = verdict.needPass,
            priority = decision.priority,
            reasonTag = "arbiter_lock",
        )
    }
}

/**
 * 2026-11-18 裁决器影子对比与 H5 执法开关（默认开）：
 * - `arbiterEnforce == false`：只计算并记分歧到 files/loop/arbiter-diff.jsonl，不执法；
 * - `arbiterEnforce == true`：由 [evaluateForEnforce] 给出唯一裁决，同时永不锁/测试模式/紧急解除仍优先于一切。
 */
object ArbiterShadow {
    private const val TAG = "DshArbiterShadow"
    private const val DEDUPE_MS = 10 * 60_000L
    private const val PREFS = "dsh_arbiter_enforce"
    private const val KEY_ENFORCE = "arbiter_enforce"
    @Volatile private var current: ArbiterDecision? = null
    @Volatile private var receivedAt: Long = 0L
    @Volatile private var enforceCache: Boolean? = null
    private val lastLogged = HashMap<String, Long>()

    fun enforceEnabled(ctx: Context): Boolean {
        enforceCache?.let { return it }
        return runCatching {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val persisted = if (prefs.contains(KEY_ENFORCE)) prefs.getBoolean(KEY_ENFORCE, true) else null
            ArbiterEnforcePolicy.resolveEnforceSwitch(persisted)
        }.getOrDefault(ArbiterEnforcePolicy.DEFAULT_ENFORCE_ENABLED).also { enforceCache = it }
    }

    fun setEnforceEnabled(ctx: Context, on: Boolean) {
        if (enforceCache == on) return
        enforceCache = on
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENFORCE, on).apply()
        }
        Log.i(TAG, "arbiterEnforce=$on")
    }

    fun evaluateForEnforce(ctx: Context, pkg: String?): ArbiterEnforceOutcome? {
        if (!enforceEnabled(ctx)) return null
        val p = pkg?.trim().orEmpty()
        val released = runCatching { ProactiveRuntime.hasUnifiedRelease() }.getOrDefault(false) ||
            runCatching { InterceptRuntime.isBoutBufferActive() }.getOrDefault(false)
        return ArbiterEnforcePolicy.evaluateAt(
            arbiterEnforce = true,
            // 过期的裁决（引擎超过 10 分钟没给新的）不执法，退回旧路径。
            decision = current,
            receivedAt = receivedAt,
            now = System.currentTimeMillis(),
            pkg = p,
            neverLock = p.isNotEmpty() && LockSafety.neverLock(ctx, p),
            disarmed = LockSafety.disarmed(ctx),
            shadow = LockSafety.shadow(ctx),
            isSystem = p.isNotEmpty() && SessionFactsOf.isSystem(ctx, p),
            released = released,
        )
    }

    fun isNeedPass(ctx: Context, pkg: String?): Boolean {
        if (!enforceEnabled(ctx)) return false
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return false
        val d = currentFresh() ?: return false
        return p in d.needPass
    }

    fun update(raw: Any?) {
        current = ArbiterDecision.fromWire(raw)
        receivedAt = if (current != null) System.currentTimeMillis() else 0L
    }

    /** 吊销「Live 在线免锁」瞬态缓存，防止切出后沿用旧免锁态滑进娱乐 App。 */
    fun invalidateIfLiveKeepAliveUnlock() {
        val cur = current ?: return
        if (cur.source == "GEMINI_LIVE_KEEPALIVE_UNLOCK" ||
            (cur.mode == ArbiterDecision.MODE_FREE && cur.priority == "P2")
        ) {
            current = null
            receivedAt = 0L
        }
    }

    /** 常驻 Live 掉线超缓冲时在端侧立即武装 P2 GEMINI_LIVE_CELL 白名单，不等 60s 周期 /plan。 */
    fun armLocalLiveDropLock(allowPackages: Set<String>, why: String, unlockHow: String) {
        val cur = current
        if (cur != null && cur.priority in setOf("P0", "P1", "P3")) return
        current = ArbiterDecision(
            mode = ArbiterDecision.MODE_ALLOWLIST,
            allow = allowPackages,
            why = why,
            unlockHow = unlockHow,
            priority = "P2",
            source = "GEMINI_LIVE_CELL",
        )
        receivedAt = System.currentTimeMillis()
    }

    fun current(): ArbiterDecision? = current

    fun currentFresh(now: Long = System.currentTimeMillis()): ArbiterDecision? =
        current?.takeIf { ArbiterDecision.isFresh(receivedAt, now) }

    /** 底座：永不锁那一层，加上系统自带的 App（宁可放过）。 */
    fun isBase(ctx: Context, pkg: String): Boolean =
        LockSafety.neverLock(ctx, pkg) || SessionFactsOf.isSystem(ctx, pkg)

    fun observe(ctx: Context, pkg: String?) {
        val d = current ?: return
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return
        val verdict = d.decide(p) { isBase(ctx, it) }
        val old = runCatching {
            ProactiveRuntime.wouldLock(p) ||
                (InterceptRuntime.enabled() && InterceptRuntime.config().targetFor(p) != null)
        }.getOrDefault(false)
        if (verdict.lock == old) return
        val now = System.currentTimeMillis()
        val key = p + ":" + verdict.lock
        synchronized(lastLogged) {
            val last = lastLogged[key] ?: 0L
            if (now - last < DEDUPE_MS) return
            lastLogged[key] = now
        }
        runCatching {
            val f = File(File(ctx.filesDir, "loop"), "arbiter-diff.jsonl")
            f.parentFile?.mkdirs()
            if (f.length() > 512 * 1024) f.writeText("")
            val line = org.json.JSONObject()
                .put("at", now).put("pkg", p).put("mode", d.mode).put("priority", d.priority)
                .put("newLock", verdict.lock).put("oldLock", old).put("why", verdict.why).put("needPass", verdict.needPass)
            f.appendText(line.toString() + "\n")
        }.onFailure { Log.w(TAG, "diff log", it) }
        Log.i(TAG, "diff $p new=${verdict.lock} old=$old mode=${d.mode} (${verdict.why})")
    }
}
