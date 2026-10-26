package com.yishou.app.scroll

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷开关“考我一下”：刷着刷着想练了，下拉一下点它。刷着练没开时，点它就开。
 * 自己在系统的快捷开关编辑里加上才会出现。
 */
class ScrollTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val on = ScrollService.running.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "考我一下"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) "刷着练开着" else "点了先开刷着练"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = if (ScrollService.running.value) ScrollActivity.askIntent(this) else ScrollActivity.startIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 49, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
