package com.zongkong.sentinel.watch

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.zongkong.sentinel.health.KeyguardFacts
import com.zongkong.sentinel.health.LockClassifier
import com.zongkong.sentinel.health.LockState

/**
 * 设备管理员锁屏 + 观测（报告 §4/§8，规范 §5，T09/T10）。
 *
 * 与 [`com.zongkong.sentinel.DeviceLock`] 的分工：
 *  - 本文件只做 `DevicePolicyManager.lockNow()` 与**观测回执**；无条件、只要管理员已激活。
 *  - `DeviceLock` 只负责防卸载（本轮**不启用**）。
 *
 * 铁律：
 *  - **不把「没抛异常」当成功**：调用完立刻读 KeyguardManager/PowerManager，观测不到就是 UNVERIFIED。
 *  - 设备没设安全锁屏时 `lockNow()` 可能只是休眠 → 报 UNVERIFIED 并说明原因（T09）。
 *  - **不改、不读、不重置**用户锁屏凭据。
 */
class LockExecutor(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun adminComponent(): ComponentName =
        ComponentName(context.packageName, DEVICE_ADMIN_CLASS)

    /** 管理员是否已激活（用户手动激活一次即可；本轮不要求 Device Owner）。 */
    fun adminActive(): Boolean = try {
        dpm?.isAdminActive(adminComponent()) == true
    } catch (_: Throwable) {
        false
    }

    fun isDeviceOwner(): Boolean = try {
        dpm?.isDeviceOwnerApp(context.packageName) == true
    } catch (_: Throwable) {
        false
    }

    /** 设备是否设置了安全锁屏（PIN/图案/密码）。 */
    fun secureLockSet(): Boolean = try {
        keyguard?.isDeviceSecure == true
    } catch (_: Throwable) {
        false
    }

    /** 一次性读回可观测事实；读不到返回 null（→ UNVERIFIED，绝不是成功）。 */
    fun facts(): KeyguardFacts? {
        return try {
            val km = keyguard ?: return null
            KeyguardFacts(
                keyguardShowing = km.isKeyguardLocked,
                deviceLocked = km.isDeviceLocked,
                secureLockSet = km.isDeviceSecure,
                interactive = power?.isInteractive ?: true,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "读锁屏状态失败", e)
            null
        }
    }

    data class Outcome(
        val state: LockState,
        val note: String,
        val facts: KeyguardFacts?,
        val adminActive: Boolean,
    )

    /**
     * 执行一次锁屏并观测。
     *
     * [observeForMs]：调用后在这段时间内轮询观测（默认 1.5s）。观测不到就 UNVERIFIED。
     * 全程**不抛异常出去**：失败也只是一条回执。
     */
    fun lockNow(observeForMs: Long = 1_500): Outcome {
        val admin = adminActive()
        if (!admin) {
            // 没有管理员权限：明确区分「无权限」，不假装锁上了（T09）。
            return Outcome(
                state = LockState.FAILED,
                note = "设备管理员未激活，无法使用 lockNow；需要手动激活一次",
                facts = facts(),
                adminActive = false,
            )
        }
        var error: Throwable? = null
        val called = try {
            dpm?.lockNow()
            true
        } catch (e: Throwable) {
            error = e
            Log.w(TAG, "lockNow 失败", e)
            false
        }
        if (!called) {
            val (state, note) = LockClassifier.classify(false, error, facts())
            return Outcome(state, note, facts(), admin)
        }
        // 调用返回 ≠ 锁上了。等一小会儿再观测：系统锁屏有延时。
        val deadline = System.currentTimeMillis() + observeForMs
        var f = facts()
        while (System.currentTimeMillis() < deadline) {
            if (f != null && f.deviceLocked && f.keyguardShowing) break
            try { Thread.sleep(150) } catch (_: InterruptedException) { break }
            f = facts()
        }
        val (state, note) = LockClassifier.classify(true, null, f)
        return Outcome(state, note, f, admin)
    }

    /** 界面用的一句真话。 */
    fun capabilityText(): String = when {
        !adminActive() -> "设备管理员未激活：无法用系统接口锁屏。到「设备管理员」里给「总控哨兵」勾上。"
        !secureLockSet() -> "设备管理员已激活，但设备没有设置安全锁屏：lockNow 可能只是休眠，回执会记「未验证」。"
        else -> "设备管理员已激活，且有安全锁屏：可以锁屏并观测结果。"
    }

    companion object {
        private const val TAG = "SentinelLock"
        /** 与 AndroidManifest 里 receiver 的类名一致。 */
        const val DEVICE_ADMIN_CLASS = "com.zongkong.sentinel.AdminReceiver"
    }
}
