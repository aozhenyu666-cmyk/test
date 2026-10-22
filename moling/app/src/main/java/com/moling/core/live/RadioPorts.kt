package com.moling.core.live

/*
 * 第十一批 R4 / R6 的**接口约定**（本波没有实现，也没有接任何权限或音频焦点）。
 *
 * 这里只写清楚 App 侧适配器要实现什么，绝不假装已经能出声 / 能听：
 *  - [RadioAudioPort]：电台一段一段念。每次回调都要带 session / segment / generation，
 *    适配器自己也要在出声前再问一次 [RadioEngine.accepts]（停止、跳过、换会话后旧回调不能出声）。
 *  - [VoiceSessionPort]：只有用户显式开启“对讲”才建；停止 / 撤权限 / 离开会话必须释放麦克风。
 *  - [HudDialPort]：桌宠轻轮盘（破雾 / 捉虫 / 只听电台 / 先安静），关闭不抢焦点。
 *  - [AudioFocusPort]：音频焦点、来电 / 导航 / 拔耳机的暂停/退回文字；不硬改系统媒体音量。
 *
 * 实现说明与未完成项写在 moling/DEVLOG.md 与 V13-PROGRESS.md；本轮只保证状态机与文字面板可用。
 */

/** 电台的念读适配器（系统 TTS / 云 TTS / MediaPlayer 都行）。 */
interface RadioAudioPort {
    /** 念这一段。适配器在真正出声前应再检查 [RadioEngine.accepts]；[done] 只回调一次。 */
    fun play(session: RadioSession, segment: RadioSegment, done: (ok: Boolean) -> Unit)

    /** 立即停：暂停 / 跳过 / 停止 / 离开页面都调，且不能触发“下一段自动开播”。 */
    fun stop()

    /** 彻底释放（页面销毁 / 服务停止）。 */
    fun release()
}

/** 显式语音会话（R6）：只有用户点了才启动 SpeechRecognizer；后台弹窗不能自己开麦。 */
interface VoiceSessionPort {
    val active: Boolean

    /** [onText] 在主线程回调；[onError] 里要说明可读原因并停止监听。 */
    fun start(onText: (String) -> Unit, onError: (String) -> Unit)

    fun stop()

    fun release()
}

/** 桌宠轻轮盘（R6）：只做已有浮层区域内的点选，不抢焦点、不点别的应用。 */
interface HudDialPort {
    fun show(options: List<String>, onPick: (String) -> Unit)

    fun hide()
}

/** 音频焦点 / 中断策略（R6）：来电、导航播报、拔耳机时暂停或退回文字；正常 / 出错 / 取消都释放。 */
interface AudioFocusPort {
    /** 请求焦点；返回 false 表示没有拿到（可能正在通话），调用方应退回文字或静默。 */
    fun acquire(): Boolean

    /** 是否被系统 duck / 暂时失去焦点。 */
    val interrupted: Boolean

    fun release()
}
