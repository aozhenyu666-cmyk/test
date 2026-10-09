package com.zongkong.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.DayOps
import com.zongkong.core.Verdict

/**
 * 调试版专用：CI 冒烟测试通过 adb 预置状态。
 *   adb shell am broadcast -a com.zongkong.app.SEED -p com.zongkong.app --es blocked com.android.contacts
 *   adb shell am broadcast -a com.zongkong.app.SEED -p com.zongkong.app --es mode free
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = context.zk.store
        intent.getStringExtra("blocked")?.let { list ->
            store.updateConfig { it.copy(blocked = list.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }
        }
        when (intent.getStringExtra("mode")) {
            "free" -> {
                val now = System.currentTimeMillis()
                store.updateToday(now) { day ->
                    var d = DayOps.checkin(day, "冒烟测试：报到一次", now)
                    store.status(now).gates.filter { !it.done }.forEach { g ->
                        d = DayOps.submit(d, g, "冒烟测试", Verdict(true, "测试", "测试通过"), now)
                    }
                    d
                }
                store.updateConfig { it.copy(silenceHours = 0, dailyQuotaMin = 0) }
            }
        }
        Log.i("SeedReceiver", "seeded: strict=${store.status().strict} blocked=${store.config.value.blocked}")
    }
}
