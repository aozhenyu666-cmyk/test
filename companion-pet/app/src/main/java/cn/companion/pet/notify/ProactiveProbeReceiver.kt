package cn.companion.pet.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.companion.pet.core.proactive.DeliveryRecord
import kotlinx.coroutines.runBlocking

/**
 * 通知通道的自检入口。
 *
 * 为什么需要它：通知是主动交流的主通道，但它的可用性不该靠"等模型生成一句话"来验证——
 * 那样一旦模型/网络/Key 出问题，就分不清是「通知坏了」还是「生成坏了」。
 *
 * 用法（开发者/验收用，不暴露给普通用户）：
 * ```
 * adb shell am broadcast -a cn.companion.pet.action.PROBE_NOTIFICATION \
 *     --es text "这是一条测试消息" -p cn.companion.pet
 * ```
 * 收到后立刻发一条真实通知，并把结果打到日志（tag=PetNotify）与 [lastResult]。
 */
class ProactiveProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_PROBE) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            ?: "通知通道自检：如果你看到这条，说明主动消息能送到。"

        val notifier = ProactiveNotifier(context.applicationContext)
        val available = notifier.available()
        val record = DeliveryRecord(
            id = "probe-" + System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            text = text,
            notificationId = PROBE_NOTIFICATION_ID,
        )
        val ok = runBlocking { notifier.send(record) }
        lastResult = if (ok) "sent" else if (!available) "channel-unavailable" else "send-failed"
        android.util.Log.i(
            TAG,
            "probe: available=" + available + " send=" + ok + " result=" + lastResult,
        )
    }

    companion object {
        const val ACTION_PROBE = "cn.companion.pet.action.PROBE_NOTIFICATION"
        const val EXTRA_TEXT = "text"
        const val TAG = "PetNotify"

        /** 自检用固定 id，避免刷屏。 */
        const val PROBE_NOTIFICATION_ID = 90_001

        /** 最近一次自检结果：sent / channel-unavailable / send-failed。 */
        @Volatile
        var lastResult: String = "not-run"
            private set
    }
}
