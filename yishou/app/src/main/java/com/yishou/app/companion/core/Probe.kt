package com.yishou.app.companion.core

/**
 * 陪练引擎要验的四项能力。首版只在一手内部用，名字保持通用，方便以后看题场景复用。
 */
enum class Capability(val label: String) {
    /** 选定 App 的声音能不能录到（AudioPlaybackCapture + AudioRecord） */
    CAPTURE_AUDIO("内录声音"),

    /** 画面关键帧能不能拿到（MediaProjection） */
    CAPTURE_FRAME("关键帧"),

    /** 目标播放器能不能暂停并核实（MediaSession） */
    PLAYBACK_CONTROL("暂停/恢复"),

    /** 投屏和麦克风有没有被别人占着 */
    EXCLUSIVE_OWNER("资源互斥"),
}

/**
 * 一次探针的结论。特意把「没有有效声音」和「不支持」分开：
 * 内录成功但目标 App 静音，用户看到的是「当前没有有效声音」，不是「这个 App 不行」。
 */
enum class ProbeVerdict(val label: String) {
    OK("可用"),
    SILENT("没有有效声音"),
    UNSUPPORTED("不支持"),
    DENIED("没有授权"),
    FAILED("失败"),
    UNKNOWN("未验证"),
}

/**
 * 一项能力的结论 + 凭什么这么判。
 *
 * 硬规矩：判「可用」必须有实际观察到的证据（读到多少帧、听到多久有声、暂停后状态是什么）。
 * 没证据就降级成「未验证」——发送成功不等于暂停成功，授权成功不等于录得到声音。
 */
data class ProbeResult(
    val capability: Capability,
    val verdict: ProbeVerdict,
    val evidence: String,
    val checkedAt: Long,
) {
    companion object {
        /** 没证据的 OK 一律改成 UNKNOWN，避免把「发出去指令」写成「已经做到」 */
        fun of(capability: Capability, verdict: ProbeVerdict, evidence: String, at: Long): ProbeResult {
            val text = evidence.trim()
            return if (verdict == ProbeVerdict.OK && text.isEmpty()) {
                ProbeResult(capability, ProbeVerdict.UNKNOWN, "没拿到实际证据，不记为可用", at)
            } else {
                ProbeResult(capability, verdict, text, at)
            }
        }
    }
}

/** 探针结果表：每项能力只留最新一条。 */
data class ProbeReport(val results: List<ProbeResult> = emptyList()) {

    fun of(capability: Capability): ProbeResult? = results.lastOrNull { it.capability == capability }

    fun with(result: ProbeResult): ProbeReport =
        copy(results = results.filterNot { it.capability == result.capability } + result)

    fun usableCount(): Int = results.count { it.verdict == ProbeVerdict.OK }

    /** 有没有还值得再试的项（全部 OK 就不催用户重试） */
    fun needsRetry(): Boolean = results.any { it.verdict != ProbeVerdict.OK }
}
