package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.InterceptRuntime
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFactsOf
import java.io.File

/**
 * 2026-11-18 裁决器影子对比：新裁决只计算、不执法。每次切 App，和旧逻辑比一下，
 * 结论不一样就记一行到 files/loop/arbiter-diff.jsonl（同一个 App 同一种分歧 10 分钟只记一次）。
 * 攒几天看这份记录：分歧都在预期内，再把手机切成只认新裁决。
 */
object ArbiterShadow {
    private const val TAG = "DshArbiterShadow"
    private const val DEDUPE_MS = 10 * 60_000L
    @Volatile private var current: ArbiterDecision? = null
    private val lastLogged = HashMap<String, Long>()

    fun update(raw: Any?) {
        current = ArbiterDecision.fromWire(raw)
    }

    fun current(): ArbiterDecision? = current

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
