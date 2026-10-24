package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.net.CallStatus
import cn.companion.pet.net.CallStatusSink

/** 最近一次调用模型的结论（成功/失败原因/时间）。不存请求和回复内容。 */
class CallStatusPrefs(context: Context) : CallStatusSink {
    private val prefs = context.applicationContext.getSharedPreferences("call_status", Context.MODE_PRIVATE)

    override fun record(status: CallStatus) {
        prefs.edit()
            .putBoolean(KEY_OK, status.ok)
            .putString(KEY_MESSAGE, status.message)
            .putLong(KEY_AT, status.at)
            .apply()
    }

    fun last(): CallStatus? {
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        return CallStatus(prefs.getBoolean(KEY_OK, false), prefs.getString(KEY_MESSAGE, "").orEmpty(), at)
    }

    /** 换了 Key / 地址 / 模型后，旧的失败原因不再适用。 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_OK = "ok"
        private const val KEY_MESSAGE = "message"
        private const val KEY_AT = "at"
    }
}
