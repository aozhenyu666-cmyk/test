package com.zongkong.sentinel

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/*
 * 防卸载（0.15，可选）：普通安装的 App 挡不住自己被卸载。唯一真正挡得住的，是把哨兵设成
 * 「设备所有者」（Device Owner）——要在一台没登录账号的手机上用 adb 执行一次。
 *
 *   adb shell dpm set-device-owner com.zongkong.sentinel/.AdminReceiver
 *
 * 设成之后，哨兵可以锁住自己不被卸载（setUninstallBlocked）。解除闸门放行「准备卸载」时才临时解锁。
 * 不想要了：先 adb 撤销（dpm remove-active-admin），才能卸载。没设也行，只是卸载挡不住、只能提醒。
 *
 * v2 说明（规范 §5.1、报告 §13）：
 *  - **本轮不启用防卸载**：`ENABLED = false`，[sync] 直接返回，不再自动设卸载锁。
 *  - 本文件与锁屏无关；管理员锁屏在 `watch/LockExecutor`（lockNow + KeyguardManager 观测）。
 *  - 设备管理员本身仍要在系统设置里激活一次，force-lock 才能用。
 */
object DeviceLock {
    private const val TAG = "DeviceLock"

    /** 防卸载本轮不启用（规范 §5.1 第 4 条）。改这个开关前要先有真机验收记录。 */
    const val ENABLED = false

    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /** 是不是设备所有者（能真正锁卸载）。 */
    fun isOwner(c: Context): Boolean = try {
        dpm(c)?.isDeviceOwnerApp(c.packageName) == true
    } catch (_: Throwable) {
        false
    }

    /** 锁 / 解锁自己的卸载。只有设备所有者能做；返回是否成功。本轮不启用。 */
    fun lockUninstall(c: Context, locked: Boolean): Boolean {
        if (!ENABLED) return false
        if (!isOwner(c)) return false
        return try {
            dpm(c)?.setUninstallBlocked(admin(c), c.packageName, locked)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "设卸载锁失败", e)
            false
        }
    }

    /** 按现在该不该锁，调整卸载锁（解除「准备卸载」生效时解锁，否则锁上）。本轮不启用。 */
    fun sync(c: Context) {
        if (!ENABLED) return
        if (!isOwner(c)) return
        val req = Store.release(c)
        val unlockedForUninstall = req != null && req.kind == Release.Kind.UNINSTALL &&
            Release.granted(req, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), Store.releaseRule)
        lockUninstall(c, !unlockedForUninstall)
    }

    fun setupOwnerCommand() = "adb shell dpm set-device-owner com.zongkong.sentinel/.AdminReceiver"
    fun releaseOwnerCommand() = "adb shell dpm remove-active-admin com.zongkong.sentinel/.AdminReceiver"
}

/** 设备管理器组件：设成设备所有者时需要它。本身不做额外的事。 */
class AdminReceiver : DeviceAdminReceiver()
