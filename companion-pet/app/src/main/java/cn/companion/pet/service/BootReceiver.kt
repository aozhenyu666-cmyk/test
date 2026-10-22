package cn.companion.pet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机或更新后自动拉起（用户主动停止过则不拉起）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PetService.startIfAllowed(context)
        }
    }
}
