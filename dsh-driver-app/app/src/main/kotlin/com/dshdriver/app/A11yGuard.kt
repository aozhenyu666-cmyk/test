package com.dshdriver.app

import android.Manifest
import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 无障碍服务的"活着"和我能把它救回来。
 *
 * 为什么需要这个：无障碍服务是绑在进程上的，进程被划掉 / 被强制停止后，系统会把它从
 * `enabled_accessibility_services` 里删掉。而"把自己写回去"这件事需要进程活着 + 有写 secure settings 的权限，
 * 所以它自己救不了自己，只能靠三种外力：
 *
 * 1. 本 APP 自己就有 WRITE_SECURE_SETTINGS（adb / Shizuku 授予过一次就一直在）→ 直接写；
 * 2. 没有，但有 Shizuku → 用 shell 身份写（Curbox 走的就是这条路）；
 * 3. 都没有 → 发通知让人手动开，首页也会显示"救不回来"。
 *
 * 写回永远是**只追加自己**，不覆盖别人的字符串——整串覆盖会把用户其他无障碍服务一起关掉。
 */
object A11yGuard {
    private const val KEY_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private const val KEY_ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED
    private const val NOTE_ID = 3
    private const val CHANNEL = "driver"

    fun component(context: Context): String = ComponentName(context, WatchService::class.java).flattenToString()

    /** 系统现在有没有在跑我们的无障碍服务：组件在列表里，且总开关是 1。 */
    fun isOn(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!enabled) return false
        val me = component(context)
        val on = runCatching { Settings.Secure.getString(context.contentResolver, KEY_SERVICES) }.getOrNull().orEmpty()
        return on.split(':').any { it.trim().equals(me, ignoreCase = true) }
    }

    /** 自己有写 secure settings 的权限（adb / Shizuku 授过一次，重装才会丢）。 */
    fun canWriteDirectly(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 现在能不能自己救回来（任何一种方式都行）。 */
    fun canHeal(context: Context): Boolean =
        canWriteDirectly(context) || DriverDeviceAdmin.isOwner(context) || Shell.granted()

    /**
     * 开着的就别动；关着就尽量写回去。
     * 同步能判断的部分同步做，走 Shizuku 的那条是异步的，所以返回值只代表"这一刻是否已确认可用"。
     */
    fun ensure(context: Context): Boolean {
        if (isOn(context)) return true
        if (canWriteDirectly(context)) return writeDirectly(context)
        // 设备所有者不需要 WRITE_SECURE_SETTINGS，也不依赖 Shizuku 是否活着
        if (DriverDeviceAdmin.isOwner(context)) return writeViaOwner(context)
        if (Shell.granted()) {
            writeViaShell(context)
            return false // 异步，等它自己写完；下次心跳再复查
        }
        return false
    }

    /** 只追加指定组件，保留别人原有的。 */
    private fun merged(current: String?, vararg add: String): String {
        val set = LinkedHashSet<String>()
        current.orEmpty().split(':').map(String::trim).filter { it.isNotEmpty() }.forEach { set.add(it) }
        add.filter { it.isNotBlank() }.forEach { set.add(it) }
        return set.joinToString(":")
    }

    /** 可选互保：总控、一手的无障碍组件。只追加，不关别人；默认关，用户在首页手动开。 */
    private val PEERS = mapOf(
        "com.zongkong.app" to "com.zongkong.app/com.zongkong.app.guard.GuardService",
        "com.yishou.app" to "com.yishou.app/com.yishou.app.gate.GateService",
    )

    /**
     * 把总控/一手被系统摘掉的无障碍也补回去。需要本 APP 已经有直接写权限或设备所有者；
     * 只写 enabled_accessibility_services，不碰 accessibility_enabled 之外的东西，也不删任何组件。
     */
    fun ensurePeers(context: Context): Boolean = runCatching {
        if (!Engine.store.settings().peerGuard) return false
        val owner = DriverDeviceAdmin.isOwner(context)
        if (!canWriteDirectly(context) && !owner) return false
        val current = Settings.Secure.getString(context.contentResolver, KEY_SERVICES)
        val present = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val missing = PEERS.filter { (pkg, comp) -> installed(context, pkg) && comp !in present }.values.toList()
        if (missing.isEmpty()) return true
        val value = merged(current, *missing.toTypedArray())
        if (owner) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            val admin = DriverDeviceAdmin.component(context)
            dpm.setSecureSetting(admin, KEY_SERVICES, value)
            dpm.setSecureSetting(admin, KEY_ENABLED, "1")
        } else {
            Settings.Secure.putString(context.contentResolver, KEY_SERVICES, value)
            Settings.Secure.putInt(context.contentResolver, KEY_ENABLED, 1)
        }
        true
    }.getOrDefault(false)

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun writeDirectly(context: Context): Boolean = runCatching {
        val me = component(context)
        val current = Settings.Secure.getString(context.contentResolver, KEY_SERVICES)
        val listed = current.orEmpty().split(':').any { it.trim().equals(me, true) }
        val enabled = Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1
        if (listed && enabled) return true
        // 组件在列表里但总开关被关掉（有些 ROM 会这样）也要把开关写回 1
        if (!listed) Settings.Secure.putString(context.contentResolver, KEY_SERVICES, merged(current, me))
        Settings.Secure.putInt(context.contentResolver, KEY_ENABLED, 1)
        val ok = isOn(context)
        if (ok) context.applicationContext.let { Engine.store.saveHeal(System.currentTimeMillis()) }
        ok
    }.getOrDefault(false)

    /**
     * 设备所有者写回：DevicePolicyManager.setSecureSetting 不需要 WRITE_SECURE_SETTINGS，
     * 也不会因为 Shizuku 没启动而失效。仍然只追加自己，不动别人的无障碍服务。
     */
    private fun writeViaOwner(context: Context): Boolean = runCatching {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = DriverDeviceAdmin.component(context)
        val me = component(context)
        val current = Settings.Secure.getString(context.contentResolver, KEY_SERVICES)
        val listed = current.orEmpty().split(':').any { it.trim().equals(me, true) }
        val enabled = Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 0) == 1
        if (!listed) dpm.setSecureSetting(admin, KEY_SERVICES, merged(current, me))
        if (!listed || !enabled) dpm.setSecureSetting(admin, KEY_ENABLED, "1")
        val ok = isOn(context)
        if (ok) context.applicationContext.let { Engine.store.saveHeal(System.currentTimeMillis()) }
        ok
    }.getOrDefault(false)

    private fun writeViaShell(context: Context) {
        val me = component(context)
        val current = runCatching { Settings.Secure.getString(context.contentResolver, KEY_SERVICES) }.getOrNull()
        val value = merged(current, me)
        Shell.run("settings put secure enabled_accessibility_services '$value'; settings put secure accessibility_enabled 1") { ok, _ ->
            if (ok) Engine.store.saveHeal(System.currentTimeMillis())
        }
    }

    /**
     * 用 Shizuku 把"以后都不用再麻烦 Shizuku"的那一步做掉：
     * 直接授予 WRITE_SECURE_SETTINGS，并把 APP 放进 doze 白名单、放开后台限制。
     * 这几条正是 Curbox 的 ServiceProtectionManager.healNow() 做的事，只是它每次都靠 Shizuku，我们只做一次。
     */
    fun harden(context: Context, done: (Boolean) -> Unit = {}) {
        if (!Shell.granted()) {
            done(false)
            return
        }
        val pkg = context.packageName
        val cmd = listOf(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        ).joinToString("; ")
        Shell.run(cmd) { ok, _ -> done(ok) }
    }

    /** 救不回来时提醒一次，别每 15 分钟响一次。 */
    fun notifyDown(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_driver)
            .setContentTitle("拦截停摆了：无障碍被系统关掉")
            .setContentText("点开重新打开；想让它自己恢复，就在首页用 Shizuku 授权一次。")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTE_ID, n) }
    }

    fun clearDownNotice(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
    }

    /** 首页要显示的几种状态，用一句话说清楚现在靠什么活着。 */
    fun describe(context: Context): String = when {
        isOn(context) && DriverDeviceAdmin.isOwner(context) -> "已开，设备所有者会直接写回去"
        isOn(context) && canWriteDirectly(context) -> "已开，掉了我能自己写回去"
        isOn(context) && Shell.granted() -> "已开，掉了我能用 Shizuku 写回去"
        isOn(context) -> "已开，但掉了要手动重开"
        DriverDeviceAdmin.isOwner(context) -> "被关掉了，设备所有者会写回去"
        canWriteDirectly(context) -> "被关掉了，下次启动会写回去"
        Shell.granted() -> "被关掉了，下次启动会用 Shizuku 写回去"
        else -> "没开；需要在系统设置里打开"
    }
}
