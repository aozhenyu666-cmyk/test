package io.github.ccch1mneyyy.dsh_app.tile

import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.R
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.ccch1mneyyy.dsh_app.shortcuts.EntryActivity

/**
 * Quick Settings tile 「局」: opens the 局 panel (through shortcuts/EntryActivity, kind `bouts`).
 * An action, not a switch: always inactive, with today's beat count as its second line (otherwise
 * the system writes 「关闭」 there). On the lock screen it asks to unlock first.
 *
 * The count comes from a summary the plugin writes whenever it renders a 局 panel
 * (`bouts-status.json`, see `boutsSnapshot` in dsh-go-companion/lib/index.js): the tile cannot
 * read the plugin's sqlite, and the plugin cannot push to a tile. Until a 局 panel has been opened
 * once the file does not exist and the second line falls back to the app's name.
 */
class BoutsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = AppStrings.get(this, R.string.tile_bouts)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = todayLine() ?: applicationInfo.loadLabel(packageManager)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        val intent = EntryActivity.bouts(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 「今日 N 拍」, prefixed while a 局 is still running. Null when the plugin never wrote a
     * summary — the file only appears once a 局 panel has been rendered at least once.
     */
    private fun todayLine(): String? {
        val status = BoutsTileStatus.read(filesDir) ?: return null
        val beats = BoutsTileStatus.beats(status) ?: return null
        return if (BoutsTileStatus.running(status)) {
            AppStrings.get(this, R.string.tile_bouts_running, beats)
        } else {
            AppStrings.get(this, R.string.tile_bouts_today, beats)
        }
    }
}
