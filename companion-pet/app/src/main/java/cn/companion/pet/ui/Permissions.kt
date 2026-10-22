package cn.companion.pet.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import cn.companion.pet.a11y.PerceptionService

/** 引导页要检查的各项权限和跳转入口。 */
object Permissions {

    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    fun accessibility(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, PerceptionService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun mic(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notifications(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun batteryUnrestricted(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun overlayIntent(c: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    @Suppress("BatteryLife") // 自用常驻 App，需要豁免电池优化才能不被杀
    fun batteryIntent(c: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))

    fun appDetails(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    /**
     * vivo / iQOO 的“自启动”和“后台高耗电”管理页。不同系统版本入口不同，挨个试；
     * 都打不开就退回应用详情页。
     */
    fun vivoAutoStartIntents(): List<Intent> = listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity"),
    ).map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** 依次尝试，成功打开一个就返回 true。 */
    fun tryStart(c: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    fun isVivo() = Build.MANUFACTURER.equals("vivo", ignoreCase = true) || Build.BRAND.equals("iqoo", ignoreCase = true)
}
