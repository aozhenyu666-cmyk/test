package com.yishou.app.companion.core

/**
 * 视频陪练启动前该说什么。
 *
 * 之前没配文字模型也照样开：它安静地听几分钟、一个字都不说，用户根本不知道是坏了还是没配。
 * 现在缺什么就直说，并且**不启动**——开一场没有任何输出的会话比不开更糟。
 */
data class CompanionReady(
    val blockers: List<String>,
    val notes: List<String>,
) {
    val canStart: Boolean get() = blockers.isEmpty()
    fun firstMessage(): String = (blockers + notes).firstOrNull().orEmpty()
}

object CompanionChecks {

    /**
     * @param llmReady 文字模型配好了吗（出题和判定都要它）
     * @param sttReady 听写接口配好了吗（没有就听不到内容）
     * @param canRecord 有没有录音权限（内录也要）
     * @param notifyAllowed 通知权限（切到别的 App 后靠它显示状态和停）
     */
    fun startup(llmReady: Boolean, sttReady: Boolean, canRecord: Boolean, notifyAllowed: Boolean): CompanionReady {
        val blockers = buildList {
            if (!llmReady) add("还没填文字模型：它只能听，不会问。去 设置 填好再来。")
            if (!canRecord) add("没有录音权限：内录开不了。先去权限页给「录音」。")
        }
        val notes = buildList {
            if (!sttReady) add("没填听写接口：听不到内容，只能显示有没有声音。去 设置 → 语音 填一个。")
            if (!notifyAllowed) add("通知被关着：切到视频 App 后看不到「一手 · 视频陪练」和「停」。")
        }
        return CompanionReady(blockers, notes)
    }
}
