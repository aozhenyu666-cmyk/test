package com.yishou.app.judge

/** 锁题：题面展示前固定要点；参考答案只在揭晓时展示。 */
data class LockedQ(val id: String, val prompt: String, val options: List<String>, val points: List<Point>, val need: Int, val reference: String)
data class Point(val id: String, val criterion: String)
data class ProbeTrace(val shownAt: Long, val firstInputAt: Long?, val submitAt: Long, val deletes: Int, val pauses: List<Pause>, val pastedRanges: List<IntRange>, val viaVoice: Boolean)
data class Pause(val atChar: Int, val millis: Long)
data class Verdict(val hits: Map<String, String?>, val offTopic: Boolean, val passed: Boolean, val note: String)
interface Judge {
    suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace): Verdict
}
