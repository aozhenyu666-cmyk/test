package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist

/** 纯判定（JVM 可测）：代理连着就直接开 Gemini；没连且装了代理 App 就先开代理；都没有就直接试。 */
object ProxyGatePolicy {
    /** 优先级：NekoBox 在前。 */
    val PROXY_ORDER = listOf("moe.nb4a", "io.nekohasekai.sagernet", "io.nekohasekai.sfa", "com.v2ray.ang",
        "com.github.metacubex.clash.meta", "com.github.kr328.clash")

    fun choose(vpnUp: Boolean, installed: Set<String>): String? {
        if (vpnUp) return null
        return PROXY_ORDER.firstOrNull { it in installed }
    }
}

object ProxyGate {
    private const val TAG = "DshProxyGate"
    private const val WAIT_MS = 90_000L
    private const val POLL_MS = 2_000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var waiting: Runnable? = null

    sealed interface Step {
        data object Direct : Step
        data class OpenProxyFirst(val pkg: String) : Step
    }

    fun vpnUp(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    private fun installed(ctx: Context): Set<String> = SystemSafeAllowlist.PROXY_PACKAGES.filter { pkg ->
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
    }.toSet()

    fun before(ctx: Context): Step {
        val pkg = ProxyGatePolicy.choose(vpnUp(ctx), installed(ctx)) ?: return Step.Direct
        return Step.OpenProxyFirst(pkg)
    }

    /** 打开代理 App，等系统里出现 VPN 再执行 [then]；超时只提示，不硬开 Gemini（没代理开了也连不上）。 */
    fun openProxyThen(ctx: Context, pkg: String, then: () -> Unit) {
        val app = ctx.applicationContext
        waiting?.let { main.removeCallbacks(it) }
        val launch = app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a11y = io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService.instance
        val opened = launch != null && (
            (a11y != null && runCatching { a11y.startActivity(launch) }.isSuccess) ||
                runCatching { app.startActivity(launch) }.isSuccess
            )
        main.post {
            runCatching {
                Toast.makeText(app, if (opened) "先在 NekoBox 里点连接，连上后自动打开 Gemini" else "代理没连上：先打开 NekoBox 连接，再开 Gemini", Toast.LENGTH_LONG).show()
            }
        }
        if (!opened) return
        val deadline = System.currentTimeMillis() + WAIT_MS
        val tick = object : Runnable {
            override fun run() {
                if (vpnUp(app)) {
                    waiting = null
                    Log.i(TAG, "proxy up, launching Gemini")
                    then()
                    return
                }
                if (System.currentTimeMillis() > deadline) {
                    waiting = null
                    Log.i(TAG, "proxy not up in time")
                    runCatching { Toast.makeText(app, "90 秒内没连上代理，这次没开 Gemini", Toast.LENGTH_LONG).show() }
                    return
                }
                main.postDelayed(this, POLL_MS)
            }
        }
        waiting = tick
        main.postDelayed(tick, POLL_MS)
    }
}
