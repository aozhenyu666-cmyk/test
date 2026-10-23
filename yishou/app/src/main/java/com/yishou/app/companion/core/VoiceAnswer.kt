package com.yishou.app.companion.core

/**
 * 语音作答的开关规则。
 *
 * 只在「问题已经念完、在等你答」这一段开麦：正在问、正在判、已经停了都不开。
 * 听到的内容为空、或者听写失败，都退回打字，绝不记成答错。
 *
 * 纯逻辑：真开麦、真转写在 Android 侧，这里只决定「这段该不该听」和「听不清怎么说」。
 */
object VoiceAnswer {

    /** 这一段该不该开麦。只在等回答时开：念问题的时候开麦会把自己的朗读录进去。 */
    fun shouldListen(phase: Phase): Boolean = phase == Phase.WAIT_ANSWER

    /** 转写结果能不能当成回答：只有真的说了字才算，空白不算。 */
    fun accept(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 没听清时给用户的话。
     * 说清还能怎么办（打字），不把听写故障算成用户没答。
     */
    fun missed(why: String? = null): String {
        val reason = why?.trim().orEmpty()
        return if (reason.isEmpty()) "没听清，打字也行" else "没听清（$reason），打字也行"
    }
}
