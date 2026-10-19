package com.yishou.app.speech

/**
 * 语音口令：一句很短的话正好是这些意思时，当作操作而不是回答。
 * 只认短句（去掉标点后不超过 8 个字），长一点的话一律当回答，避免误触。
 */
enum class VoiceCommand { DONT_KNOW, REPEAT, PAUSE, DEMO, ROLL, THINKING, CANCEL }

object VoiceCommands {
    private const val MAX_CHARS = 8

    private val table = listOf(
        VoiceCommand.DONT_KNOW to listOf("我不知道", "不知道", "不会", "想不出来", "没思路"),
        VoiceCommand.REPEAT to listOf("再说一遍", "重复一遍", "重复", "再读一遍", "没听清", "再说一次"),
        VoiceCommand.PAUSE to listOf("先停", "暂停", "休息一下", "歇一下", "我要休息"),
        VoiceCommand.DEMO to listOf("示范", "看个示范", "给个示范", "举个例子", "示范一下"),
        VoiceCommand.ROLL to listOf("换一手", "掷骰子", "换一个", "换个问题", "掷骰"),
        VoiceCommand.THINKING to listOf("我在想", "等一下", "等等", "让我想想", "我想想"),
        VoiceCommand.CANCEL to listOf("取消", "算了", "不对重来", "重来"),
    )

    fun parse(text: String): VoiceCommand? {
        val t = text.filter { Character.isLetterOrDigit(it.code) }
        if (t.isEmpty() || t.length > MAX_CHARS) return null
        return table.firstOrNull { (_, words) -> words.any { t == it || (t.length <= it.length + 2 && t.contains(it)) } }?.first
    }
}
