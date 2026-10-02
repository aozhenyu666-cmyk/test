package com.behaviordept.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/** 长按桌面图标出现的两个快捷入口：“我想刷”和“登记一局”。 */
object Shortcuts {
    fun install(context: Context) {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        fun shortcut(id: String, short: Int, long: Int, icon: Int): ShortcutInfo =
            ShortcutInfo.Builder(context, id)
                .setShortLabel(context.getString(short))
                .setLongLabel(context.getString(long))
                .setIcon(Icon.createWithResource(context, icon))
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_ROUTE, id),
                )
                .build()
        runCatching {
            sm.dynamicShortcuts = listOf(
                shortcut(MainActivity.ROUTE_URGE, R.string.shortcut_urge, R.string.shortcut_urge_long, R.drawable.ic_shortcut_urge),
                shortcut(MainActivity.ROUTE_MATCH, R.string.shortcut_match, R.string.shortcut_match_long, R.drawable.ic_shortcut_match),
            )
        }
    }
}
