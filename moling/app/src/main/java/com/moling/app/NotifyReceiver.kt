package com.moling.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知上的「好 / 不用」（M11）：用户点按钮就算“点好”，和 App 里点卡片同一条路。
 * 总控那边仍然要再点一次「执行」；这里只是把提议执行掉/关掉。
 */
class NotifyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.moling.app.notify.ACCEPT"
        const val ACTION_DECLINE = "com.moling.app.notify.DECLINE"
        const val EXTRA_PROPOSAL = "proposal"
        const val EXTRA_KIND = "kind"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_ACCEPT && action != ACTION_DECLINE) return
        val id = intent.getStringExtra(EXTRA_PROPOSAL).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty().ifBlank { "notice" }
        if (id.isBlank()) return

        // goAsync：广播回来前把协程跑完，别让系统把进程收了。
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val text = if (action == ACTION_ACCEPT) {
                    runCatching { BrainHost.accept(id, System.currentTimeMillis()) }.getOrNull()?.message
                        ?: "没接上，打开墨灵再试"
                } else {
                    runCatching { BrainHost.decline(id, System.currentTimeMillis()) }
                    "好，这条不用了"
                }
                Notify.updateResult(context.applicationContext, kind, text)
            } finally {
                pending.finish()
            }
        }
    }
}
