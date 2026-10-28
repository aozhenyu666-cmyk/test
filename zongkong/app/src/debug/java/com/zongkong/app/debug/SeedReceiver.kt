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
 *   adb shell am broadcast -a com.zongkong.app.SEED -p com.zongkong.app --es mode hq       （开始一次总控时间）
 *   adb shell am broadcast -a com.zongkong.app.SEED -p com.zongkong.app --es mode hq-end   （提前结束）
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = context.zk.store
        intent.getStringExtra("blocked")?.let { list ->
            store.updateConfig { it.copy(blocked = list.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }
        }
        when (intent.getStringExtra("mode")) {
            // 严管：清掉今天的记录和端到端测试留下的事项，加一道全天“开放即拦截”的关卡
            "strict" -> {
                val now = System.currentTimeMillis()
                store.updateWork { com.zongkong.core.work.Work() }
                store.updateToday(now) { com.zongkong.core.DayLog(it.date) }
                store.updateConfig {
                    it.copy(
                        gates = com.zongkong.core.Defaults.gates() + com.zongkong.core.Gate(
                            id = "smoke", dept = com.zongkong.core.Dept.HQ, title = "冒烟测试", instruction = "测试用",
                            openAt = 4 * 60, deadline = 4 * 60 - 1, block = com.zongkong.core.BlockMode.FROM_OPEN,
                        ),
                        silenceHours = 0, dailyQuotaMin = 0, pausedUntil = 0,
                    )
                }
            }
            "hq" -> context.zk.actions.hq.start(com.zongkong.core.hq.HqTrigger.MANUAL)
            // 模拟总控掉线：给哨兵的心跳写成很久以前
            "main-down" -> com.zongkong.app.guard.SentinelClient.push(context, store.config.value.blocked, true, 0L, at = 0L)
            "hq-end" -> context.zk.actions.hq.exitEarly("冒烟测试结束总控时间")
            // 放行，但打开娱乐应用先答一题（0.10 默认）
            "gate", "free" -> {
                val now = System.currentTimeMillis()
                store.updateToday(now) { day ->
                    var d = DayOps.checkin(day, "冒烟测试：报到一次", now)
                    store.status(now).gates.filter { !it.done }.forEach { g ->
                        d = DayOps.submit(d, g, "冒烟测试", Verdict(true, "测试", "测试通过"), now)
                    }
                    d
                }
                // free：纯放行（不答题、不算挣来的时长），看拦截名单本身放不放
                val gate = intent.getStringExtra("mode") == "gate"
                store.updateConfig { it.copy(silenceHours = 0, dailyQuotaMin = 0, earn = it.earn.copy(enabled = gate), watch = it.watch.copy(openGate = gate)) }
                if (gate) context.getSharedPreferences("watch", Context.MODE_PRIVATE).edit().clear().apply()
            }
        }
        Log.i("SeedReceiver", "seeded: strict=${store.status().strict} hq=${store.hq.value.active != null} blocked=${store.config.value.blocked}")
    }
}
