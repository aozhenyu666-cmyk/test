package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.tile.BoutsTileStatus

/**
 * Notification-line helper for [io.github.ccch1mneyyy.dsh_app.runtime.DshService] showing the
 * active 局 name, current beat prompt/nudge, countdown chronometer, and quick engine-restart
 * action without bloating DshService.
 */
object DafeiyuServiceHooks {
    const val ACTION_RESTART_ENGINE = "io.github.ccch1mneyyy.dsh_app.RESTART_ENGINE"

    fun addRestartAction(context: Context, builder: Notification.Builder) {
        val restartPi = PendingIntent.getService(
            context,
            3,
            Intent(context, DshService::class.java).setAction(ACTION_RESTART_ENGINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(Notification.Action.Builder(null, "重启引擎", restartPi).build())
    }

    fun boutLineAndApplyChronometer(context: Context, builder: Notification.Builder, hasHigherPriorityLine: Boolean): String? {
        if (hasHigherPriorityLine) return null
        val bouts = BoutsTileStatus.read(context.filesDir)
        if (!BoutsTileStatus.running(bouts)) return null
        val nudge = BoutsTileStatus.nudge(bouts)
        val boutLine = if (nudge.isNotEmpty()) {
            nudge
        } else {
            listOf(BoutsTileStatus.bout(bouts), BoutsTileStatus.ask(bouts))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
                .takeIf { it.isNotEmpty() }
        }
        if (boutLine != null) {
            val until = BoutsTileStatus.until(bouts)
            if (until > System.currentTimeMillis()) {
                builder.setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setChronometerCountDown(true)
                builder.setWhen(until)
            }
        }
        return boutLine
    }
}
