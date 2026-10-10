package app.jobtracker.ui

import android.content.Context
import app.jobtracker.R
import app.jobtracker.ai.ModelError

/** 把模型调用错误翻译成给用户看的提示 */
fun ModelError.describe(context: Context): String = when (this) {
    ModelError.NoApiKey -> context.getString(R.string.error_no_api_key)
    ModelError.NoNetwork -> context.getString(R.string.error_no_network)
    ModelError.Timeout -> context.getString(R.string.error_timeout)
    ModelError.NetworkFailure -> context.getString(R.string.error_network)
    ModelError.Refused -> context.getString(R.string.error_refused)
    ModelError.InvalidOutput -> context.getString(R.string.error_invalid_output)
    is ModelError.Http -> when (code) {
        401, 403 -> context.getString(R.string.error_http_auth)
        429 -> context.getString(R.string.error_http_rate_limit)
        in 500..599 -> context.getString(R.string.error_http_busy)
        else -> context.getString(R.string.error_http_other, code, message.orEmpty())
    }
}
