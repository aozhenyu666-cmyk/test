package io.github.ccch1mneyyy.dsh_app.fish.guardian.watch

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.KeyguardFacts
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockClassifier
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockState

class GuardianDeviceAdminReceiver : DeviceAdminReceiver()

/**
 * Executes `DevicePolicyManager.lockNow()` when enabled by user and observes actual Keyguard state.
 */
class LockExecutor(private val context: Context) {
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun adminComponent(): ComponentName =
        ComponentName(context, GuardianDeviceAdminReceiver::class.java)

    fun adminActive(): Boolean = runCatching {
        dpm?.isAdminActive(adminComponent()) == true
    }.getOrDefault(false)

    fun secureLockSet(): Boolean = runCatching {
        keyguard?.isDeviceSecure == true
    }.getOrDefault(false)

    fun facts(): KeyguardFacts? = runCatching {
        val km = keyguard ?: return@runCatching null
        KeyguardFacts(
            keyguardShowing = km.isKeyguardLocked,
            deviceLocked = km.isDeviceLocked,
            secureLockSet = km.isDeviceSecure,
            interactive = power?.isInteractive ?: true,
        )
    }.getOrNull()

    data class Outcome(val state: LockState, val note: String)

    fun lockNow(): Outcome {
        if (!adminActive()) return Outcome(LockState.FAILED, "未激活设备管理员（仅显示模态提醒）")
        var called = false
        var err: Throwable? = null
        try {
            dpm?.lockNow()
            called = true
        } catch (t: Throwable) {
            err = t
        }
        if (called) runCatching { Thread.sleep(250L) }
        val (state, note) = LockClassifier.classify(called, err, facts())
        return Outcome(state, note)
    }
}
