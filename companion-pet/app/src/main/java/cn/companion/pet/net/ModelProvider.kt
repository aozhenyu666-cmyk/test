package cn.companion.pet.net

import java.net.URI

/**
 * 常用的 OpenAI 兼容服务。设置页一键填好地址和模型名，都还能手动改。
 * 模型名会随服务商更新，填的是写这段代码时的常用低价档。
 */
enum class ModelProvider(val label: String, val shortLabel: String, val endpoint: String, val model: String) {
    DEEPSEEK("DeepSeek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    OPENAI("GPT（OpenAI）", "GPT", "https://api.openai.com/v1", "gpt-4.1-mini"),
    QWEN("千问（阿里云百炼）", "千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ;

    companion object {
        /**
         * OpenAI 官方接口的新模型不再接受 max_tokens，要用 max_completion_tokens；
         * 其他兼容服务大多只认 max_tokens。
         */
        fun usesCompletionTokens(endpoint: String): Boolean {
            val host = runCatching { URI(endpoint.trim()).host }.getOrNull()?.lowercase() ?: return false
            return host == "api.openai.com" || host.endsWith(".openai.com")
        }
    }
}
