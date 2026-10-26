package com.yishou.app.healthwire

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.yishou.app.health.KeyguardFacts
import com.yishou.app.health.LockClassifier
import com.yishou.app.health.LockState
import com.yishou.app.health.Mono
import com.yishou.app.health.Wall

/**
 * 一手的设备管理员（规范 §5.1 第 1 条：首选设备管理员 force-lock）。
 *
 * 只申请 `<uses-policies><force-lock /></uses-policies>` 这一项：
 * 不申请清除数据、不申请重置密码、不申请锁屏密码策略，也不激活任何防卸载（规范 §5.1 第 4 条）。
 * 用户随时可以在系统设置里停用这个管理员。
 */
class YishouAdmin : DeviceAdminReceiver() {
    companion object {
        const val DESCRIPTION = "让「一手」在监管时段内能锁屏。只申请锁屏这一项能力，不碰你的锁屏密码和数据。"
    }
}

/**
 * 本地锁屏执行器（规范 §5.1 / §5.2）。
 *
 * 关键立场（报告 T09/T10）：
 *  - **调用返回 ≠ 锁上了**：必须读 KeyguardManager / PowerManager 去观测，观测不到就 UNVERIFIED；
 *  - 没设安全锁屏时 `lockNow()` 可能只是休眠，**不得报成安全锁定**（[LockClassifier] 已保证）；
 *  - 不读、不设、不重置用户的锁屏凭据（规范 §5.1 第 6 条）。
 */
object LocalLockExecutor {

    private const val TAG = "LocalLock"

    /** 权限状态：设备管理员激活、是否有安全锁屏、无障碍在不在（能否走第二条通道）。 */
    data class Perms(
        val adminActive: Boolean,
        val secureLockSet: Boolean,
        val accessibilityOn: Boolean,
    ) {
        /** 有没有一条能真正锁屏的通道。 */
        val canLock: Boolean get() = adminActive || (secureLockSet && accessibilityOn)
    }

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, YishouAdmin::class.java)

    fun perms(context: Context, accessibilityOn: Boolean): Perms {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = try {
            dpm?.isAdminActive(adminComponent(context)) == true
        } catch (e: Exception) {
            Log.w(TAG, "读设备管理员状态失败", e)
            false
        }
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        val secure = try {
            km?.isDeviceSecure == true
        } catch (e: Exception) {
            false
        }
        return Perms(adminActive = admin, secureLockSet = secure, accessibilityOn = accessibilityOn)
    }

    /** 打开系统页让用户激活设备管理员（不能自己激活，必须用户在系统界面上点）。 */
    fun requestAdmin(context: Context): Boolean = try {
        val intent = android.content.Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, YishouAdmin.DESCRIPTION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "打不开设备管理员页面", e)
        false
    }

    data class Outcome(val state: LockState, val note: String, val atWall: Long)

    /**
     * 执行一次锁屏并**观测**结果。
     *
     * @param mono 单调时钟（记录用）。
     * @param wall 墙上时钟（回执用）。
     * @param observeDelayMs 调 API 之后等多久再读锁屏状态（系统锁屏有几十毫秒的延迟）。
     */
    fun lockNow(
        context: Context,
        mono: Mono,
        wall: Wall,
        observeDelayMs: Long = 400L,
    ): Outcome {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        var called = false
        var error: Throwable? = null
        try {
            val comp = adminComponent(context)
            if (dpm != null && dpm.isAdminActive(comp)) {
                dpm.lockNow()
                called = true
            } else {
                error = IllegalStateException("设备管理员未激活，无法 force-lock")
            }
        } catch (e: Throwable) {
            error = e
            Log.w(TAG, "lockNow 失败", e)
        }
        if (!called) {
            val (state, note) = LockClassifier.classify(false, error, null)
            return Outcome(state, note, wall.now())
        }
        // 等一下再观测：立刻读往往还是解锁态。
        try {
            Thread.sleep(observeDelayMs.coerceIn(0L, 2_000L))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val facts = readFacts(context)
        val (state, note) = LockClassifier.classify(true, null, facts)
        return Outcome(state, note, wall.now())
    }

    /** 从系统读到的事实；拿不到时返回 null（→ UNVERIFIED，不猜）。 */
    fun readFacts(context: Context): KeyguardFacts? = try {
        val km = context.getSystemService(android.app.KeyguardManager::class.java) ?: return null
        val pm = context.getSystemService(PowerManager::class.java)
        KeyguardFacts(
            keyguardShowing = km.isKeyguardLocked,
            deviceLocked = if (Build.VERSION.SDK_INT >= 22) km.isDeviceLocked else km.isKeyguardLocked,
            secureLockSet = km.isDeviceSecure,
            interactive = pm?.isInteractive ?: true,
        )
    } catch (e: Exception) {
        Log.w(TAG, "读锁屏状态失败", e)
        null
    }
}
