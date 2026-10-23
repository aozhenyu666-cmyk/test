package com.yishou.app.companion.core

/** 播放器此刻在干什么（不引用 Android 的 PlaybackState，纯逻辑能测） */
enum class PlayState {
    PLAYING,
    PAUSED,
    STOPPED,
    UNKNOWN;

    companion object {
        /**
         * 把 PlaybackState 的整数状态翻译过来；认不出的一律 UNKNOWN，不猜。
         * 缓冲、拖动、报错这些中间态也不当「停住了」——它们随时会自己接着放。
         */
        fun of(code: Int): PlayState = when (code) {
            3 -> PLAYING
            2 -> PAUSED
            0, 1 -> STOPPED
            else -> UNKNOWN
        }
    }
}

/** 暂停这件事到底做到没有 */
enum class PauseOutcome(val label: String, val verdict: ProbeVerdict) {
    VERIFIED("已暂停（核实过状态）", ProbeVerdict.OK),
    STILL_PLAYING("发了指令但还在放", ProbeVerdict.UNKNOWN),
    NO_EVIDENCE("看不到播放状态", ProbeVerdict.UNKNOWN),
    WAS_IDLE("本来就没在放", ProbeVerdict.UNKNOWN),
    UNSUPPORTED("没有可控制的播放会话", ProbeVerdict.UNSUPPORTED),
}

/**
 * 暂停的判据：只认「发完指令之后再看一眼状态」。
 *
 * 发送成功、AudioFocus 拿到、系统媒体键按下，都不等于已经停了。
 * 看不到目标的播放状态时，只能说没证据，让界面提示用户手动暂停。
 */
object PauseProof {

    fun judge(sent: Boolean, before: PlayState, after: PlayState): PauseOutcome = when {
        !sent -> PauseOutcome.UNSUPPORTED
        // 发指令之前就没在放：停没停是我们按的和它没关系，不能说「我暂停成功」
        before == PlayState.PAUSED || before == PlayState.STOPPED -> PauseOutcome.WAS_IDLE
        after == PlayState.PAUSED || after == PlayState.STOPPED -> PauseOutcome.VERIFIED
        after == PlayState.PLAYING && before == PlayState.PLAYING -> PauseOutcome.STILL_PLAYING
        else -> PauseOutcome.NO_EVIDENCE
    }

    /** 恢复同样要核实：只在「本次确实是自己停的」前提下才允许恢复 */
    fun canResume(outcome: PauseOutcome, stillSameSession: Boolean, userTouched: Boolean): Boolean =
        outcome == PauseOutcome.VERIFIED && stillSameSession && !userTouched
}
