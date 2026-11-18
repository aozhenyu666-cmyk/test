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
    const val DAILY_DISARM_CAP = 3
    const val DISARM_MINUTES = 10
    private const val CHANNEL = "dsh_lock_safety"
    private const val NOTIF_ID = 0x5AFE
    const val ACTION_DISARM = "io.github.ccch1mneyyy.dsh_app.DISARM"
    const val EXTRA_REASON = "reason"
    const val EXTRA_MINUTES = "minutes"
    private const val TOAST_GAP_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shadowCache: Boolean? = null
    @Volatile private var disarmCache: Long = -1L
    @Volatile private var ime: Set<String> = emptySet()
    private val lastToast = HashMap<String, Long>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 永不锁

    fun neverLock(ctx: Context, pkg: String?): Boolean {
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

    fun usedToday(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString(KEY_DISARM_DAY, "") == today()) p.getInt(KEY_DISARM_COUNT, 0) else 0
    }

    sealed interface DisarmResult {
        data class Ok(val until: Long, val usedToday: Int) : DisarmResult
        data class Refused(val why: String) : DisarmResult
    }

    /**
     * 本地解除所有锁 [minutes] 分钟：不经过引擎（引擎卡死时也能用）。
     * [counted] = 走通知栏的那条（要理由、每天 3 次）；adb 的开发者通道不计次。
     */
    fun disarm(ctx: Context, reason: String, minutes: Int = DISARM_MINUTES, counted: Boolean = true): DisarmResult {
        val why = reason.trim()
        if (counted && why.length < 2) return DisarmResult.Refused("要写一句理由")
        val used = usedToday(ctx)
        if (counted && used >= DAILY_DISARM_CAP) return DisarmResult.Refused("今天的 $DAILY_DISARM_CAP 次紧急解除已经用完")
        val now = System.currentTimeMillis()
        val until = now + minutes.coerceIn(1, 120) * 60_000L
        disarmCache = until
        val nextUsed = if (counted) used + 1 else used
        runCatching {
            prefs(ctx).edit()
                .putLong(KEY_DISARM_UNTIL, until)
                .putString(KEY_DISARM_DAY, today())
                .putInt(KEY_DISARM_COUNT, nextUsed)
                .apply()
        }
        main.post {
            runCatching { InterceptPageActivity.withdrawAll() }.onFailure { Log.w(TAG, "withdraw intercept", it) }
            runCatching { FocusPageActivity.withdrawAll() }.onFailure { Log.w(TAG, "withdraw focus", it) }
            runCatching { ProactiveRuntime.onLocalDisarm(until, why.ifBlank { "adb" }, counted) }.onFailure { Log.w(TAG, "notify runtime", it) }
            runCatching {
                Toast.makeText(ctx.applicationContext, "已紧急解除 ${minutes} 分钟（今天第 $nextUsed 次），这件事会记进今晚的账", Toast.LENGTH_LONG).show()
            }
        }
        Log.i(TAG, "disarm until=$until counted=$counted reason=$why")
        return DisarmResult.Ok(until, nextUsed)
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
            val left = (DAILY_DISARM_CAP - usedToday(ctx)).coerceAtLeast(0)
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
