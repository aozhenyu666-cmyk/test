package cn.companion.pet.health

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import cn.companion.pet.a11y.PerceptionHub

/**
 * 设备管理员组件。只为 `force-lock` 存在：本应用**不做**防卸载、不做 Device Owner、
 * 不改用户锁屏凭据（报告 §4、WIRE-SPEC-v2 §5.1）。
 */
class PetDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("PetDeviceAdmin", "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("PetDeviceAdmin", "device admin disabled")
    }

    companion object {
        fun componentName(context: Context) = ComponentName(context, PetDeviceAdmin::class.java)
    }
}

/**
 * 真实的锁屏通道。
 *
 * 两条通道分开看（规范 §5.1）：
 *  1. 设备管理员 `lockNow()` —— 首选，用户通过系统界面激活。
 *  2. 无障碍 `GLOBAL_ACTION_LOCK_SCREEN` —— API 28+ 的另一条，不能当唯一兜底。
 * 没设置安全锁屏时 `lockNow()` 可能只是休眠，这个判断交给 [LockClassifier]，这里不粉饰。
 */
class AndroidLockGateway(
    private val context: Context,
    private val a11yService: () -> Any? = { PerceptionHub.service },
) : LockGateway {

    private val dpm: DevicePolicyManager? by lazy {
        runCatching { context.getSystemService(DevicePolicyManager::class.java) }.getOrNull()
    }

    override fun isAdminActive(): Boolean = runCatching {
        dpm?.isAdminActive(PetDeviceAdmin.componentName(context)) == true
    }.getOrDefault(false)

    override fun lockNow(): Boolean {
        val admin = dpm ?: return false
        if (!isAdminActive()) return false
        return runCatching {
            admin.lockNow()
            true
        }.getOrElse {
            Log.w("AndroidLockGateway", "lockNow failed", it)
            false
        }
    }

    override fun globalLockAction(): Boolean {
        val svc = a11yService() as? android.accessibilityservice.AccessibilityService ?: return false
        return runCatching { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
            .getOrDefault(false)
    }

    override fun keyguardFacts(): KeyguardFacts? = runCatching {
        val kg = context.getSystemService(KeyguardManager::class.java) ?: return null
        val pm = context.getSystemService(PowerManager::class.java) ?: return null
        KeyguardFacts(
            keyguardShowing = kg.isKeyguardLocked,
            deviceLocked = kg.isDeviceLocked,
            secureLockSet = kg.isDeviceSecure,
            interactive = pm.isInteractive,
        )
    }.getOrNull()

    /** 跳系统界面让用户激活设备管理员。只能由用户点一下触发，不自动弹。 */
    fun requestAdminIntent(): Intent = runCatching {
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PetDeviceAdmin.componentName(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "只用来在你自己设定的监管窗口到期时锁屏一次。不读取、不修改你的锁屏密码，也不防卸载。",
            )
    }.getOrElse { Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS) }
}
