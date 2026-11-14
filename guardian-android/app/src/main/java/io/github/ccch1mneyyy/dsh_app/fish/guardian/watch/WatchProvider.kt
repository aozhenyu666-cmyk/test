package io.github.ccch1mneyyy.dsh_app.fish.guardian.watch

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassQuota
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockState

class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ArcLog.append(context, "收到系统唤醒：${intent?.action ?: "unknown"}，启动哨兵")
        WatchService.start(context)
    }
}

/**
 * Internal non-exported provider bridging MainActivity / GuardianA11yService and `:watch` process.
 */
class WatchProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { WatchService.start(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = context ?: return Bundle()
        val stores = WatchBus.stores ?: GuardianStores(c.filesDir)
        val clock = WatchBus.clock ?: io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ClampedWallClock(
            wall = { System.currentTimeMillis() },
            mono = { android.os.SystemClock.elapsedRealtime() },
        )
        val nowMono = clock.monoNow()
        val nowWall = clock.now()

        return when (method) {
            "status" -> {
                val snap = stores.loadSnapshot()
                val settings = stores.loadSettings()
                val pass = stores.loadPass()
                val mirror = stores.loadMirror()
                Bundle().apply {
                    putBoolean("watchRunning", WatchService.running)
                    putString("snapshotJson", GuardianJson.encode(snap.toMap()))
                    putBoolean("modalOnEngineDown", settings.modalOnEngineDown)
                    putBoolean("lockOnEngineDown", settings.lockOnEngineDown)
                    putBoolean("autoRebindA11y", settings.autoRebindA11y)
                    putBoolean("passActive", EmergencyPassQuota.isActive(pass, nowMono))
                    putLong("passRemainingMs", (pass.activeUntilMono - nowMono).coerceAtLeast(0L))
                    putInt("passRemainingToday", EmergencyPassQuota.remainingCount(pass, nowWall))
                    putInt("blockedCount", mirror.blocked.size)
                    putBoolean("mirrorActive", mirror.hasActiveEnforcement(nowWall))
                    putString("primaryWhy", mirror.primaryWhy)
                    putString("logsText", ArcLog.recent(c, 15).joinToString("\n"))
                }
            }
            "probeNow" -> {
                val svc = WatchBus.service
                val snap = svc?.evaluateCycle(forceProbe = true) ?: stores.loadSnapshot()
                Bundle().apply {
                    putBoolean("ok", true)
                    putString("stateName", snap.stateName)
                    putString("statusReason", snap.statusReason)
                    putString("enginePhase", snap.enginePhase)
                }
            }
            "wakeNow" -> {
                val svc = WatchBus.service
                val out = svc?.performWake(allowActivityFallback = true)
                    ?: run {
                        val pkg = FishClientBridge.resolveInstalledTarget(c)
                        if (pkg == null) FishClientBridge.WakeOutcome(false, "未安装大肥鱼")
                        else FishClientBridge.wakeEngine(c, pkg, true)
                    }
                Bundle().apply {
                    putBoolean("ok", out.ok)
                    putString("detail", out.detail)
                }
            }
            "grantPass" -> {
                val reason = extras?.getString("reason").orEmpty()
                val res = stores.grantEmergencyPass(reason, nowWall, nowMono)
                Bundle().apply {
                    putBoolean("ok", res.ok)
                    putString("error", res.error)
                    putInt("remainingToday", EmergencyPassQuota.remainingCount(res.state, nowWall))
                }
            }
            "saveSettings" -> {
                val next = GuardianSettings(
                    modalOnEngineDown = extras?.getBoolean("modalOnEngineDown", true) ?: true,
                    lockOnEngineDown = extras?.getBoolean("lockOnEngineDown", false) ?: false,
                    autoRebindA11y = extras?.getBoolean("autoRebindA11y", true) ?: true,
                )
                stores.saveSettings(next)
                Bundle().apply { putBoolean("ok", true) }
            }
            "testLock" -> {
                val exec = WatchBus.lockExecutor ?: LockExecutor(c)
                val out = exec.lockNow()
                ArcLog.append(c, "手动测试锁屏：${out.state} / ${out.note}")
                Bundle().apply {
                    putBoolean("ok", out.state == LockState.OBSERVED_LOCKED)
                    putString("note", "${out.state.name}：${out.note}")
                }
            }
            else -> Bundle()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "io.github.ccch1mneyyy.dsh_app.fish.guardian.watch"
        val URI: Uri = Uri.parse("content://$AUTHORITY")
    }
}
