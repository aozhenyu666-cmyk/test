package com.yishou.app.judge

/** 只记录本输入框的事件；输入法批量上屏与粘贴仅能按启发式区分。 */
class ProbeRecorder(private val clock: () -> Long) {
    private var shownAt = 0L
    private var first: Long? = null
    private var last: Long? = null
    private var deletes = 0
    private var voice = false
    private var text = ""
    private val pasted = mutableListOf<Boolean>()
    private val pauses = mutableListOf<Pause>()

    fun shown(t: Long = clock()) {
        shownAt = t; first = null; last = null; deletes = 0; voice = false; text = ""
        pasted.clear(); pauses.clear()
    }

    fun edit(old: String, new: String, t: Long = clock()) = update(old, new, t, false)
    fun voiceEdit(old: String, new: String, t: Long = clock()) = update(old, new, t, true)

    private fun update(old: String, new: String, t: Long, fromVoice: Boolean) {
        val mismatch = old != text
        if (!mismatch && old == new) return
        val previous = text
        if (mismatch) com.yishou.app.log.RunLog.e("输入探针", "输入事件对不上，按整段替换处理（原长 ${previous.length}，回填长 ${new.length}）")
        val prior = last
        if (prior != null && t - prior >= 8_000L) pauses += Pause(previous.length, t - prior)
        var start = 0
        if (!mismatch) while (start < previous.length && start < new.length && previous[start] == new[start]) start++
        var suffix = 0
        if (!mismatch) while (suffix < previous.length - start && suffix < new.length - start && previous[previous.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
        val removed = previous.length - start - suffix
        val added = new.length - start - suffix
        if (new.length < previous.length) deletes++
        repeat(removed) { pasted.removeAt(start) }
        val suspected = !fromVoice && added >= 8 && t - (prior ?: shownAt) in 0L until 100L
        pasted.addAll(start, List(added) { suspected })
        text = new
        if (first == null && new.isNotEmpty()) first = t
        last = t
        if (fromVoice) voice = true
    }

    fun paste(range: IntRange, t: Long = clock()) {
        for (i in range) if (i in pasted.indices) pasted[i] = true
        if (first == null && text.isNotEmpty()) first = t
    }
    fun voiceStart(t: Long = clock()) { voice = true; if (first == null) first = t }
    fun submit(t: Long = clock()): ProbeTrace {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < pasted.size) {
            if (!pasted[i]) { i++; continue }
            val start = i
            while (i < pasted.size && pasted[i]) i++
            ranges += start until i
        }
        return ProbeTrace(shownAt, first, t, deletes, pauses.toList(), ranges, voice)
    }
}
