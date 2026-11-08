package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.ccch1mneyyy.dsh_app.R

/** 快捷设置里的「专注监督」：点一下打开主界面；磁贴状态显示是否在监督。 */
class FocusTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        FocusRuntime.ensure(this)
        runCatching {
            startActivityAndCollapse(
                Intent(this, FocusControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val active = FocusRuntime.ensure(this).active
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "专注中" else "专注监督"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }
}
