package com.moling.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import com.moling.app.Usage

/** 旧 Usage 的十分钟窗口在长聊/十分钟约定到点时可能为空；这里读完整一天的最后一条状态。 */
object PresenceUsage {
    private var lastAt = 0L
    private var lastPkg = ""
    @Synchronized fun foreground(context: Context, now: Long): String? {
        if (!Usage.allowed(context)) { lastAt = 0; lastPkg = ""; return null }
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) return ""
        return runCatching {
            val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(if (lastAt in (now - 86_400_000L)..now) lastAt else now - 86_400_000L, now) ?: return null
            val e = UsageEvents.Event()
            var pkg = if (lastAt in (now - 86_400_000L)..now) lastPkg else ""
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> pkg = e.packageName.orEmpty()
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == pkg) pkg = ""
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> pkg = ""
                }
            }
            lastAt = now; lastPkg = pkg
            pkg
        }.getOrNull()
    }
}
