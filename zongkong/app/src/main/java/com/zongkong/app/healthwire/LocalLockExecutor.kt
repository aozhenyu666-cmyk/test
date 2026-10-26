package com.zongkong.app.healthwire

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.zongkong.app.health.KeyguardFacts
import com.zongkong.app.health.LockClassifier
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockState
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.ZkDeviceAdmin

/**
 * 本地锁屏执行器（报告 §4、规范 §5）。
 *
 * 权限路线：
 *  1. 设备管理员 `force-lock` → `DevicePolicyManager.lockNow()`（首选，独立于无障碍）；
 *  2. 没授权时**如实显示权限状态**并给出系统引导（`ACTION_ADD_DEVICE_ADMIN`），
 *     **不**退回「无障碍 GLobalAction 当作唯一兜底」，也不假装锁成功。
 *
 * 铁律：`REQUESTED → API_CALLED → OBSERVED_LOCKED / FAILED / UNVERIFIED`。
 * 没设置安全锁屏时 `lockNow()` 可能只是休眠 → 必须是 UNVERIFIED（内核 LockClassifier 已保证）。
 */
object LocalLockExecutor {

    private const val TAG = "LocalLock"

    /** 观察锁屏的时间窗：调用返回后等一小会儿再读 Keyguard。 */
    private const val OBSERVE_DELAY_MS = 700L

    data class AdminState(
        val active: Boolean,
        /** 系统里有没有这个管理员组件（能去申请）。 */
        val available: Boolean,
        val canLock: Boolean,
        /** 给用户看的一句话。 */
        val text: String,
    )

    fun admin(context: Context): AdminState {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val cn = ComponentName(context, ZkDeviceAdmin::class.java)
        val active = try {
            dpm?.isAdminActive(cn) == true
        } catch (e: Throwable) {
            Log.w(TAG, "读设备管理员状态失败", e)
            false
        }
        val canLock = active && (dpm?.isDeviceOwnerApp(context.packageName) == true || active)
        return AdminState(
            active = active,
            available = true,
            canLock = canLock,
            text = when {
                active -> "已启用设备管理员（force-lock）：锁屏不依赖无障碍"
                else -> "未启用设备管理员：无法独立锁屏，只能靠无障碍通道（不满足兜底要求）"
            },
        )
    }

    /** 引导去系统界面启用设备管理员。 */
    fun requestAdminIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, ZkDeviceAdmin::class.java))
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "允许「总控」在你设定的监管时段内锁定屏幕。它不改密码、不抹数据，也不读你的锁屏凭据。",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 用户是否设置了安全锁屏（PIN/图案/密码）。没设置时 lockNow 可能只是休眠。 */
    fun secureLockSet(context: Context): Boolean = try {
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
    } catch (e: Throwable) {
        Log.w(TAG, "读安全锁屏状态失败", e)
        false
    }

    fun facts(context: Context): KeyguardFacts? = try {
        val km = context.getSystemService(android.app.KeyguardManager::class.java) ?: return null
        val pm = context.getSystemService(PowerManager::class.java)
        KeyguardFacts(
            keyguardShowing = km.isKeyguardLocked,
            deviceLocked = km.isDeviceLocked,
            secureLockSet = km.isDeviceSecure,
            interactive = pm?.isInteractive ?: true,
        )
    } catch (e: Throwable) {
        Log.w(TAG, "读锁屏事实失败", e)
        null
    }

    /**
     * 执行一次锁屏，返回可上报的回执。
     *
     * 调用方必须已经用共享内核 [com.zongkong.app.health.LockArbiter] 判定过「该锁」
     * （去重、冷却、每事件上限都在那边）。
     */
    fun lock(context: Context, request: com.zongkong.app.health.LockRequest, nowWall: Long = System.currentTimeMillis()): LockReceipt {
        val adminState = admin(context)
        if (!adminState.active) {
            // 没权限：明确记 FAILED + 权限状态，不做任何伪装。
            runCatching { Diag.log(context, "lock_denied", "没有设备管理员权限，拒绝锁屏：${request.reason}") }
            return receipt(request, LockState.FAILED, nowWall, "没有设备管理员（force-lock）权限：${adminState.text}")
        }
        var called = false
        var err: Throwable? = null
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm?.lockNow()
            called = true
        } catch (e: Throwable) {
            err = e
            Log.w(TAG, "lockNow 失败", e)
        }
        if (!called) {
            return receipt(request, LockState.FAILED, nowWall, err?.message ?: "lockNow 没有调用成功")
        }
        // 观察：必须等一小会儿再读（不把「没抛异常」当成功）。
        val before = facts(context)
        val observed = if (before != null && (before.deviceLocked || before.keyguardShowing)) {
            before
        } else {
            runCatching {
                val latch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, OBSERVE_DELAY_MS)
                latch.await(OBSERVE_DELAY_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            facts(context)
        }
        val (state, note) = LockClassifier.classify(true, null, observed)
        runCatching {
            Diag.log(
                context,
                when (state) {
                    LockState.OBSERVED_LOCKED -> "lock_observed"
                    else -> "lock_unverified"
                },
                "$state：$note（${request.reason}）",
            )
        }
        return receipt(request, state, nowWall, note)
    }

    private fun receipt(request: com.zongkong.app.health.LockRequest, state: LockState, atWall: Long, note: String) =
        LockReceipt(
            policyId = request.policyId,
            incidentId = request.incidentId,
            requestId = request.requestId,
            source = request.source,
            reason = request.reason,
            state = state,
            atWall = atWall,
            note = note,
        )

    /** 设备管理员是否可用的时间戳（界面上「引导去开」用）。 */
    fun adminGrantedAtMono(): Long = SystemClock.elapsedRealtime()

    /** 系统版本提示：API 28+ 的无障碍锁屏通道是「另一条」，不是兜底。 */
    fun a11yLockChannelAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
