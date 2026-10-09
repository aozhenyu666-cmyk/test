package com.yishou.app.llm

/**
 * 识图：把用户拍下的作答痕迹（手写演算、做过的题）转写成文字，
 * 再和文字回答一起交给陪练判定。用设置里单独填写的识图模型。
 */
class Vision(private val chat: ChatClient) {

    suspend fun transcribe(taskTitle: String?, coachMove: String?, imageDataUrl: String): LlmResult<String> {
        val text = buildString {
            if (taskTitle != null) appendLine("学习任务：$taskTitle")
            if (coachMove != null) appendLine("陪练刚才的问题：$coachMove")
            append("这是用户回答这个问题时拍下的图片。请转写。")
        }
        return when (val r = chat.completeWithImage(Prompts.VISION, text, imageDataUrl)) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(r.value.trim().take(MAX_CHARS))
        }
    }

    companion object {
        /** 转写太长时截断，避免判定请求过大 */
        const val MAX_CHARS = 1500
    }
}
