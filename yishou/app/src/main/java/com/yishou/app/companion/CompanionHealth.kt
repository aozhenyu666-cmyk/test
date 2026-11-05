package com.yishou.app.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.yishou.app.R

/**
 * 一手无障碍服务的健康提醒。
 *
 * 两件事，分得很清：
 *  - 服务没了：发一条通知，点开直达系统无障碍设置。这是普通 APK 一定能做的。
 *  - 如果**调试授权** WRITE_SECURE_SETTINGS 在（adb pm grant），才尝试把一手自己追加回列表。
 *    普通安装没有这个权限，不能承诺静默重开；追加时保留其他已启用服务。
 */
object CompanionHealth {
    private const val CHANNEL = "companion_health"
    private const val ID = 0x9A01
    private const val ME = "com.yishou.app/com.yishou.app.gate.GateService"

    fun check(context: Context, serviceOn: Boolean) {
        if (serviceOn) cancel(context) else notifyMissing(context)
    }

    fun notifyMissing(context: Context) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "伴读需要无障碍", NotificationManager.IMPORTANCE_HIGH),
            )
            val open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_yishou)
                .setContentTitle("伴读看不到你在用什么")
                .setContentText("一手需要无障碍权限；点这里去开启")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(true)
                .build()
            nm.notify(ID, n)
        } catch (_: Throwable) {
            // 通知被系统禁用时不阻断任何东西
        }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }

    /**
     * 只在有 WRITE_SECURE_SETTINGS 时尝试；返回是否真的写成功并读回确认。
     * 不会清空其他服务，只把一手追加到现有列表末尾。
     */
    fun rebindIfAllowed(context: Context): Boolean {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            val cr = context.contentResolver
            val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val current = Settings.Secure.getString(cr, key).orEmpty()
            val parts = current.split(':').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.any { normalize(it) == ME }) return true
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val next = (parts + ME).distinctBy { normalize(it) }.joinToString(":")
            Settings.Secure.putString(cr, key, next)
            Settings.Secure.getString(cr, key).orEmpty().split(':').any { normalize(it.trim()) == ME }
        } catch (_: Throwable) {
            false
        }
    }

    private fun normalize(flat: String): String {
        val pkg = flat.substringBefore('/')
        val cls = flat.substringAfter('/', "")
        if (pkg.isEmpty() || cls.isEmpty()) return flat
        val full = if (cls.startsWith(".")) pkg + cls else cls
        return "$pkg/$full"
    }
}
