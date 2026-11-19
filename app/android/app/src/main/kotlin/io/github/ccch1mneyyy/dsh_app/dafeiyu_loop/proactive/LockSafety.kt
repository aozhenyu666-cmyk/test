package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusPageActivity
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.InterceptPageActivity
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFactsOf
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure JVM-testable emergency disarm quota & idempotent ID planner.
 * Kept separate from [LockSafety] so JVM unit tests never trigger Android `Looper.getMainLooper()`.
 */
object DisarmCapPolicy {
    const val DEFAULT_DAILY_CAP = 3
    const val DISARM_MINUTES = 10

    data class DisarmPlan(
        val allowed: Boolean,
        val nextUsed: Int,
        val nextSeq: Int,
        val disarmId: String,
        val refusedWhy: String? = null,
    )

    fun planDisarm(
        day: String,
        storedDay: String,
        storedUsed: Int,
        storedSeq: Int,
        cap: Int = DEFAULT_DAILY_CAP,
        reason: String,
        counted: Boolean = true,
        source: String = if (counted) "notification" else "adb",
    ): DisarmPlan {
        val sameDay = storedDay == day
        val used = if (sameDay) storedUsed.coerceAtLeast(0) else 0
        val seq = if (sameDay) storedSeq.coerceAtLeast(0) else 0
        val effectiveCap = cap.coerceIn(1, 10)
        val why = reason.trim()
        if (counted && why.length < 2) {
            return DisarmPlan(allowed = false, nextUsed = used, nextSeq = seq, disarmId = "", refusedWhy = "要写一句理由")
        }
        if (counted && used >= effectiveCap) {
            return DisarmPlan(
                allowed = false,
                nextUsed = used,
                nextSeq = seq,
                disarmId = "",
                refusedWhy = "今天的 $effectiveCap 次紧急解除已经用完",
            )
        }
        val nextSeq = seq + 1
        val nextUsed = if (counted) used + 1 else used
        val id = if (counted) "disarm-$day-$nextSeq" else "disarm-$day-${source.ifBlank { "adb" }}-$nextSeq"
        return DisarmPlan(allowed = true, nextUsed = nextUsed, nextSeq = nextSeq, disarmId = id)
    }
}

/**
 * 2026-11-18 锁机安全层。四条落锁入口（姐妹力度锁、拦截页、专注页、设置页自卫）落锁前都过这里：
 *
 *  1. [neverLock]：DSH 自己、默认桌面、系统界面、输入法、急事、代理永远不锁；
 *  2. [shadow]：测试模式（默认开）——锁照常算，但只弹「这里本来会锁 X」，不真锁，记进 shadow-locks.jsonl；
 *  3. [disarmed]：紧急解除——不依赖引擎，纯本地。通知栏「紧急解除」（要打一句理由，每天 3 次，每次 10 分钟）
 *     或 adb：`adb shell am broadcast -a io.github.ccch1mneyyy.dsh_app.DISARM -n <包名>/io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.LockSafetyAdbReceiver --ei minutes 30`
 */
object LockSafety {
    private const val TAG = "DshLockSafety"
    private const val PREFS = "dsh_lock_safety"
    private const val KEY_SHADOW = "shadow"
    private const val KEY_DISARM_UNTIL = "disarm_until"
    private const val KEY_DISARM_DAY = "disarm_day"
    private const val KEY_DISARM_COUNT = "disarm_count"
    private const val KEY_DISARM_SEQ = "disarm_seq"
    private const val KEY_DISARM_CAP = "disarm_daily_cap"
    private const val KEY_PENDING_DISARMS = "pending_disarms"
    const val DAILY_DISARM_CAP = 3
    const val DISARM_MINUTES = 10
    private const val MAX_PENDING_DISARMS = 20
    private const val CHANNEL = "dsh_lock_safety"
    private const val NOTIF_ID = 0x5AFE
    const val ACTION_DISARM = "io.github.ccch1mneyyy.dsh_app.DISARM"
    const val EXTRA_REASON = "reason"
    const val EXTRA_MINUTES = "minutes"
    private const val TOAST_GAP_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shadowCache: Boolean? = null
    @Volatile private var disarmCache: Long = -1L
    @Volatile private var dailyCapCache: Int = -1
    @Volatile private var ime: Set<String> = emptySet()
    private val lastToast = HashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 永不锁

    /** 插件下发的永不锁名单（/plan 的 neverLock），存 prefs，引擎断了也照用。 */
    @Volatile private var pluginNeverLock: Set<String>? = null
    private const val KEY_PLUGIN_NEVER_LOCK = "plugin_never_lock"

    fun setPluginNeverLock(ctx: Context, pkgs: Collection<String>) {
        val next = pkgs.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (next.isEmpty() || next == pluginNeverLock) return
        pluginNeverLock = next
        runCatching { prefs(ctx).edit().putStringSet(KEY_PLUGIN_NEVER_LOCK, next).apply() }
    }

    private fun pluginNeverLock(ctx: Context): Set<String> {
        pluginNeverLock?.let { return it }
        return runCatching { prefs(ctx).getStringSet(KEY_PLUGIN_NEVER_LOCK, emptySet())?.toSet() }
            .getOrNull().orEmpty().also { pluginNeverLock = it }
    }

    fun neverLock(ctx: Context, pkg: String?): Boolean {
        val p = pkg?.trim().orEmpty()
        if (p.isNotEmpty() && p in pluginNeverLock(ctx)) return true
        if (ime.isEmpty()) {
            ime = runCatching {
                ctx.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet()
            }.getOrNull().orEmpty()
        }
        return SystemSafeAllowlist.neverLock(pkg, ctx.packageName, SessionFactsOf.home(ctx), ime)
    }

    // ---------------------------------------------------------------- 测试模式（影子锁）

    /** 默认开：这一版装上后锁只提示不落，他在伴侣中枢或对姐妹说「结束测试模式」才真锁。 */
    fun shadow(ctx: Context): Boolean {
        shadowCache?.let { return it }
        return runCatching { prefs(ctx).getBoolean(KEY_SHADOW, true) }.getOrDefault(true).also { shadowCache = it }
    }

    fun setShadow(ctx: Context, on: Boolean) {
        if (shadowCache == on) return
        shadowCache = on
        runCatching { prefs(ctx).edit().putBoolean(KEY_SHADOW, on).apply() }
        Log.i(TAG, "shadow=$on")
    }

    /**
     * 测试模式下的一次「本来会锁」：弹一句提示（同一个包 20 秒内只弹一次），记一行账。
     * 返回 true = 已按测试模式处理，调用方不要再落锁。
     */
    fun shadowHit(ctx: Context, pkg: String, why: String?, source: String): Boolean {
        if (!shadow(ctx)) return false
        val now = System.currentTimeMillis()
        val key = "$source:$pkg"
        val show = synchronized(lastToast) {
            val last = lastToast[key] ?: 0L
            if (now - last >= TOAST_GAP_MS) { lastToast[key] = now; true } else false
        }
        if (show) {
            val label = runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val text = "测试模式：这里本来会锁「$label」" + (why?.takeIf { it.isNotBlank() }?.let { "——${it.take(40)}" } ?: "")
            main.post { runCatching { Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show() } }
            runCatching {
                val f = File(File(ctx.filesDir, "loop"), "shadow-locks.jsonl")
                f.parentFile?.mkdirs()
                if (f.length() > 512 * 1024) f.writeText("")
                val line = org.json.JSONObject()
                    .put("at", now).put("pkg", pkg).put("why", why ?: "").put("source", source)
                f.appendText(line.toString() + "\n")
            }
        }
        Log.i(TAG, "shadow lock ($source) $pkg: $why")
        return true
    }

    // ---------------------------------------------------------------- 紧急解除

    fun disarmed(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (disarmCache < 0) disarmCache = runCatching { prefs(ctx).getLong(KEY_DISARM_UNTIL, 0L) }.getOrDefault(0L)
        return now < disarmCache
    }

    fun disarmUntil(ctx: Context): Long {
        disarmed(ctx)
        return disarmCache
    }

    private fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    private val disarmLock = Any()

    fun dailyCap(ctx: Context): Int {
        val cached = dailyCapCache
        if (cached > 0) return cached
        return runCatching {
            prefs(ctx).getInt(KEY_DISARM_CAP, DAILY_DISARM_CAP).coerceIn(1, 10)
        }.getOrDefault(DAILY_DISARM_CAP).also { dailyCapCache = it }
    }

    fun setDailyCap(ctx: Context, cap: Int) {
        val clamped = cap.coerceIn(1, 10)
        if (dailyCapCache == clamped) return
        dailyCapCache = clamped
        runCatching { prefs(ctx).edit().putInt(KEY_DISARM_CAP, clamped).apply() }
        ensureNotification(ctx)
    }

    fun usedToday(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString(KEY_DISARM_DAY, "") == today()) p.getInt(KEY_DISARM_COUNT, 0) else 0
    }

    /** Pure JVM-testable decision & idempotent ID planner shared by notification disarm and Gemini defer. */
    fun planDisarm(
        day: String,
        storedDay: String,
        storedUsed: Int,
        storedSeq: Int,
        cap: Int,
        reason: String,
        counted: Boolean = true,
        source: String = if (counted) "notification" else "adb",
    ): DisarmCapPolicy.DisarmPlan = DisarmCapPolicy.planDisarm(
        day = day,
        storedDay = storedDay,
        storedUsed = storedUsed,
        storedSeq = storedSeq,
        cap = cap,
        reason = reason,
        counted = counted,
        source = source,
    )

    data class PendingDisarm(
        val disarmId: String,
        val reason: String,
        val until: Long,
        val counted: Boolean,
        val source: String,
    )

    private fun enqueuePendingDisarm(ctx: Context, item: PendingDisarm) {
        synchronized(disarmLock) {
            runCatching {
                val p = prefs(ctx)
                val raw = p.getString(KEY_PENDING_DISARMS, null)
                val arr = if (raw.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(raw)
                val filtered = org.json.JSONArray()
                val start = (arr.length() - (MAX_PENDING_DISARMS - 1)).coerceAtLeast(0)
                for (i in start until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("disarmId") != item.disarmId) filtered.put(obj)
                }
                filtered.put(
                    org.json.JSONObject()
                        .put("disarmId", item.disarmId)
                        .put("reason", item.reason)
                        .put("until", item.until)
                        .put("counted", item.counted)
                        .put("source", item.source),
                )
                p.edit().putString(KEY_PENDING_DISARMS, filtered.toString()).apply()
            }
        }
    }

    fun pendingDisarms(ctx: Context): List<PendingDisarm> = synchronized(disarmLock) {
        runCatching {
            val raw = prefs(ctx).getString(KEY_PENDING_DISARMS, null) ?: return@runCatching emptyList()
            val arr = org.json.JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("disarmId")
                    if (id.isBlank()) continue
                    add(
                        PendingDisarm(
                            disarmId = id,
                            reason = obj.optString("reason"),
                            until = obj.optLong("until", 0L),
                            counted = obj.optBoolean("counted", true),
                            source = obj.optString("source", "notification"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun ackDisarmSynced(ctx: Context, disarmId: String) {
        if (disarmId.isBlank()) return
        synchronized(disarmLock) {
            runCatching {
                val p = prefs(ctx)
                val raw = p.getString(KEY_PENDING_DISARMS, null) ?: return@runCatching
                val arr = org.json.JSONArray(raw)
                val next = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("disarmId") != disarmId) next.put(obj)
                }
                p.edit().putString(KEY_PENDING_DISARMS, next.toString()).apply()
            }
        }
    }

    sealed interface DisarmResult {
        data class Ok(val until: Long, val usedToday: Int, val disarmId: String = "") : DisarmResult
        data class Refused(val why: String) : DisarmResult
    }

    /**
     * 本地解除所有锁 [minutes] 分钟：不经过引擎（引擎卡死时也能用）。
     * [counted] = 走通知栏或 Gemini 延后（共享每天 [dailyCap] 次上限）；adb 的开发者通道不计次。
     */
    fun disarm(
        ctx: Context,
        reason: String,
        minutes: Int = DISARM_MINUTES,
        counted: Boolean = true,
        source: String = if (counted) "notification" else "adb",
    ): DisarmResult {
        val why = reason.trim()
        val cap = dailyCap(ctx)
        val day = today()
        val (plan, until) = synchronized(disarmLock) {
            val p = prefs(ctx)
            val storedDay = p.getString(KEY_DISARM_DAY, "").orEmpty()
            val storedUsed = p.getInt(KEY_DISARM_COUNT, 0)
            val storedSeq = p.getInt(KEY_DISARM_SEQ, 0)
            val planned = planDisarm(
                day = day,
                storedDay = storedDay,
                storedUsed = storedUsed,
                storedSeq = storedSeq,
                cap = cap,
                reason = why,
                counted = counted,
                source = source,
            )
            if (!planned.allowed) return DisarmResult.Refused(planned.refusedWhy ?: "紧急解除被拒绝")
            val nextUntil = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
            disarmCache = nextUntil
            runCatching {
                p.edit()
                    .putLong(KEY_DISARM_UNTIL, nextUntil)
                    .putString(KEY_DISARM_DAY, day)
                    .putInt(KEY_DISARM_COUNT, planned.nextUsed)
                    .putInt(KEY_DISARM_SEQ, planned.nextSeq)
                    .apply()
            }
            planned to nextUntil
        }
        val effectiveReason = why.ifBlank { "adb" }
        enqueuePendingDisarm(
            ctx,
            PendingDisarm(
                disarmId = plan.disarmId,
                reason = effectiveReason.take(80),
                until = until,
                counted = counted,
                source = source,
            ),
        )
        main.post {
            runCatching { InterceptPageActivity.withdrawAll() }.onFailure { Log.w(TAG, "withdraw intercept", it) }
            runCatching { FocusPageActivity.withdrawAll() }.onFailure { Log.w(TAG, "withdraw focus", it) }
            runCatching {
                ProactiveRuntime.onLocalDisarm(until, effectiveReason, counted, plan.disarmId, source)
            }.onFailure { Log.w(TAG, "notify runtime", it) }
            runCatching {
                val msg = if (counted) {
                    "已紧急解除 ${minutes} 分钟（今天第 ${plan.nextUsed}/$cap 次），这件事会记进今晚的账"
                } else {
                    "已紧急解除 ${minutes} 分钟（开发者通道）"
                }
                Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
            }
        }
        Log.i(TAG, "disarm id=${plan.disarmId} source=$source until=$until counted=$counted reason=$why")
        return DisarmResult.Ok(until, plan.nextUsed, plan.disarmId)
    }

    // ---------------------------------------------------------------- 通知栏入口

    /** 常驻一条低打扰通知，带「紧急解除」（RemoteInput 要求打一句理由）。幂等。 */
    fun ensureNotification(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "大肥鱼 · 锁机安全", NotificationManager.IMPORTANCE_MIN))
            }
            val input = RemoteInput.Builder(EXTRA_REASON).setLabel("为什么要解除？一句话").build()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, 0x5AFE,
                Intent(ctx, LockSafetyReceiver::class.java).setAction(ACTION_DISARM),
                flags,
            )
            val action = Notification.Action.Builder(null, "紧急解除 ${DISARM_MINUTES} 分钟", pi).addRemoteInput(input).build()
            val left = (dailyCap(ctx) - usedToday(ctx)).coerceAtLeast(0)
            val mode = if (shadow(ctx)) "测试模式：锁只提示不落" else "锁已生效"
            val n = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle("大肥鱼 · $mode")
                .setContentText("被锁住出不来时用：今天还剩 $left 次紧急解除")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(action)
                .build()
            nm.notify(NOTIF_ID, n)
        }.onFailure { Log.w(TAG, "notification", it) }
    }
}

/** 通知栏「紧急解除」：不导出，只收自己的 PendingIntent。 */
open class LockSafetyReceiver : BroadcastReceiver() {
    open val counted: Boolean = true

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockSafety.ACTION_DISARM) return
        val reason = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(LockSafety.EXTRA_REASON)?.toString()
            ?: intent.getStringExtra(LockSafety.EXTRA_REASON).orEmpty()
        val minutes = intent.getIntExtra(LockSafety.EXTRA_MINUTES, LockSafety.DISARM_MINUTES)
        val r = LockSafety.disarm(context, reason, if (counted) LockSafety.DISARM_MINUTES else minutes, counted)
        if (r is LockSafety.DisarmResult.Refused) {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(context.applicationContext, "没解除：" + r.why, Toast.LENGTH_LONG).show() }
            }
        }
        LockSafety.ensureNotification(context)
    }
}

/** adb 开发者通道：导出，但要求发送方持有 DUMP（只有 adb shell 有），普通 App 发不进来；不计次。 */
class LockSafetyAdbReceiver : LockSafetyReceiver() {
    override val counted: Boolean = false
}
