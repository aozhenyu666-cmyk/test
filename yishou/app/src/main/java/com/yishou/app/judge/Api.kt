package com.yishou.app.judge

/**
 * 判卷器的对外接口。规矩只有一条：先锁答案，再出题。
 * 要点在用户看到题之前就定死；判定只对照要点，不看字数、不看语气。
 */

/** 一个要点。criterion 只给判卷看，判完之前不给用户看；hint 是没命中时给用户的提示，不能透出判据 */
data class Point(val id: String, val criterion: String, val hint: String = "")

/** 锁题 */
data class LockedQ(
    val id: String,
    /** 给用户看的题面（不含选项） */
    val prompt: String,
    /** 可以为空；要点过了之后才亮 */
    val options: List<String> = emptyList(),
    /** 正确选项的下标；没有选项时为 -1 */
    val answer: Int = -1,
    /** 2–4 个 */
    val points: List<Point>,
    /** 至少命中几个 */
    val need: Int,
    /** 参考答案，判完才给看 */
    val reference: String,
    /** 允许粘贴（比如从外部 AI 带回来的总结）；入口题不允许 */
    val allowPaste: Boolean = false,
)

/** 时间探针记下的东西 */
data class ProbeTrace(
    val shownAt: Long,
    /** 第一个字或开始说话的时间 */
    val firstInputAt: Long?,
    val submitAt: Long,
    val deletes: Int = 0,
    /** 只记 ≥ 8 秒的停顿 */
    val pauses: List<Pause> = emptyList(),
    /** 粘贴进来的字在最终答案里的位置 */
    val pastedRanges: List<IntRange> = emptyList(),
    val viaVoice: Boolean = false,
) {
    /** 从看到题到动第一下，毫秒；一直没动就是到交卷 */
    val ttftMs: Long get() = (firstInputAt ?: submitAt) - shownAt
}

data class Pause(val atChar: Int, val millis: Long)

/** 本机直接挡下、不送去判卷的几种情况 */
enum class Block(val say: String) {
    EMPTY("没有实在的内容"),
    HOLLOW("这是一句套话，放在哪道题上都成立"),
    COPIED("基本是在复述题目，没有你自己的判断"),
    PASTED("粘贴进来的不算你的话"),
    META("答案里在指挥判卷，不算"),
}

data class Verdict(
    /** 要点 id → 引用的原话；没命中为 null */
    val hits: Map<String, String?>,
    val offTopic: Boolean,
    val passed: Boolean,
    /** 给用户看的一句话 */
    val note: String,
    /** 本机挡下的原因；送去判卷了就是 null */
    val block: Block? = null,
    /** 判卷器不在（没网、没配、格式两次都不对）。这时不放水，交给等待兜底 */
    val unavailable: Boolean = false,
    /** 判卷说命中、但原话在答案里找不到（或是抄题目的）的要点 */
    val rejected: List<String> = emptyList(),
) {
    val hitCount: Int get() = hits.values.count { it != null }
}

interface Judge {
    /**
     * seen：用户这一题已经看到过的文字（题面以外的提示等）。从这些文字里抄来的话不算命中。
     */
    suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String> = emptyList()): Verdict
}
