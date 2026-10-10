package app.jobtracker.ui.settings

import androidx.annotation.StringRes
import app.jobtracker.R

/** 校验模型接口设置，通过返回 null，不通过返回错误提示 */
@StringRes
fun validateModelSettings(apiBaseUrl: String, strongModel: String, fastModel: String): Int? = when {
    // 密钥会随请求发出，只允许加密连接
    !apiBaseUrl.trim().startsWith("https://") || apiBaseUrl.trim().length <= "https://".length ->
        R.string.settings_error_base_url
    strongModel.isBlank() || fastModel.isBlank() -> R.string.settings_error_model_blank
    else -> null
}
