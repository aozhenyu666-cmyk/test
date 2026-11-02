package com.yishou.app.focus

import com.yishou.app.think.Words

/**
 * 专注流：一段时间里只做一件事，按“工作一组、休息一下”一组一组往下走，不验收内容。
 *
 * 为什么不验收：专注流管的是“人在不在这件事上”，不是“做得对不对”。做得慢不要紧，
 * 一般的 AI 只看结果、不看时间；这里只看时间和手机在干什么。
 *
 * 手机怎么管：开着专注流时，除了系统界面、一手自己、白名单（比如 ChatGPT、Gemini）
 * 和常驻放行的通讯应用（微信、短信……），别的全拦。休息时间也一样拦——休息是站起来喝口水，
 * 不是刷视频；收费站的放行在专注流里也不算数。
 */

/** 一份专注计划。允许的应用这里存名字（模板里写的就是名字），由安卓那边解析成包名 */
data class FocusPlan(
    val task: String,
    /** 想做到的量，比如“30 道”，可以空着 */
    val goal: String = "",
    val totalMin: Int,
    val workMin: Int,
    val restMin: Int,
    val allowedNames: List<String> = emptyList(),
)

object FocusPlans {
    const val MIN_TOTAL = 10
    const val MAX_TOTAL = 240
    const val MIN_WORK = 3
    const val MAX_WORK = 60
    const val MAX_REST = 15

    /** 计划合不合格。返回 null 表示可以开始，否则是原因 */
    fun check(p: FocusPlan): String? = when {
        Words.substance(p.task) < 2 -> "写一下这一段要做什么"
        p.totalMin !in MIN_TOTAL..MAX_TOTAL -> "总时长在 $MIN_TOTAL 到 $MAX_TOTAL 分钟之间"
        p.workMin !in MIN_WORK..MAX_WORK -> "每组在 $MIN_WORK 到 $MAX_WORK 分钟之间"
        p.restMin !in 0..MAX_REST -> "休息在 0 到 $MAX_REST 分钟之间"
        p.workMin > p.totalMin -> "每组不能比总时长还长"
        else -> null
    }

    /**
     * 给 AI 照着填的模板。把它和你的情况一起发给 ChatGPT / Gemini，让它按这个格式回，再整段粘进一手。
     */
    val TEMPLATE = """
【任务】这一段要做的一件事（例：刷行测言语题）
【目标】想做到的量（例：30 道；不确定可以空着）
【总时长】60 分钟
【每组】6 分钟
【休息】1 分钟
【允许的应用】ChatGPT、Gemini（用顿号隔开；系统界面、一手、微信短信电话不用写）
""".trim()

    /** 让 AI 生成计划时附上的那句话 */
    val ASK_AI = "请根据我下面说的情况，按这个格式帮我排一个专注计划，只回这六行，不要别的：\n" + TEMPLATE

    private val TAG = Regex("^\\s*[【\\[]\\s*([^】\\]]{1,8})\\s*[】\\]]\\s*[:：]?\\s*(.*)$")

    /**
     * 读粘贴进来的计划。认【】和 []，冒号可有可无，数字取第一个。
     * 读不出任务或时长就返回 null；数值越界不在这里管，交给 [check] 说清楚。
     */
    fun parse(text: String): FocusPlan? {
        val m = mutableMapOf<String, String>()
        text.replace("```", "").lines().forEach { line ->
            TAG.find(line)?.let { r -> m.getOrPut(r.groupValues[1].trim()) { r.groupValues[2].trim() } }
        }
        fun num(vararg keys: String): Int? = keys.firstNotNullOfOrNull { m[it] }?.let { Words.numbers(it).firstOrNull()?.toInt() }
        val task = m["任务"]?.takeIf { it.isNotBlank() } ?: return null
        val total = num("总时长", "时长") ?: return null
        val work = num("每组", "工作", "一组") ?: return null
        val rest = num("休息") ?: 0
        val allowed = (m["允许的应用"] ?: m["允许"] ?: m["白名单"]).orEmpty()
            .split(Regex("[、，,;；/\\s]+")).map { it.trim() }.filter { it.isNotEmpty() && it != "无" }
            .distinct()
        return FocusPlan(task, m["目标"].orEmpty(), total, work, rest, allowed)
    }
}

enum class PhaseKind { WORK, REST, DONE }

/** 此刻在第几组、是工作还是休息、这一段什么时候结束 */
data class Phase(val kind: PhaseKind, val round: Int, val startedAt: Long, val endsAt: Long) {
    fun remainingMs(now: Long) = (endsAt - now).coerceAtLeast(0)
}

/**
 * 中途调过节奏：从调的那一刻起按新的节奏重新开一组。
 * 只能改节奏和任务说明，不能缩短总时长。
 */
data class Retune(val at: Long, val workMin: Int, val restMin: Int, val note: String = "")

/** 每组结束时用户点的一下：做完了没有，可以加一句（不验收） */
data class RoundMark(val round: Int, val at: Long, val done: Boolean, val note: String = "")

data class FocusSession(
    val plan: FocusPlan,
    val startedAt: Long,
    /** 结束时间：开始时定好，只能往后延 */
    val endsAt: Long,
    val retunes: List<Retune> = emptyList(),
    val marks: List<RoundMark> = emptyList(),
    /** 提前结束：开始等的时间（等满才真的结束） */
    val quitAskedAt: Long? = null,
    val endedAt: Long? = null,
) {
    fun active(now: Long) = endedAt == null && now < endsAt

    /** 现在处在哪一段。按“最后一次调节奏”之后的节奏一组一组往下排 */
    fun phase(now: Long): Phase {
        if (!active(now)) return Phase(PhaseKind.DONE, roundsBefore(endsAt), endsAt, endsAt)
        // 每一段节奏：从它开始到下一次调整（或结束）
        val starts = listOf(Retune(startedAt, plan.workMin, plan.restMin)) + retunes.sortedBy { it.at }
        var round = 0
        for ((i, r) in starts.withIndex()) {
            val segEnd = starts.getOrNull(i + 1)?.at ?: endsAt
            val cycle = (r.workMin + r.restMin) * 60_000L
            if (now >= segEnd) {
                round += roundsIn(r, segEnd)
                continue
            }
            val k = (now - r.at) / cycle
            val cycleStart = r.at + k * cycle
            val workEnd = cycleStart + r.workMin * 60_000L
            round += k.toInt() + 1
            return if (now < workEnd) Phase(PhaseKind.WORK, round, cycleStart, minOf(workEnd, endsAt))
            else Phase(PhaseKind.REST, round, workEnd, minOf(cycleStart + cycle, endsAt, segEnd))
        }
        return Phase(PhaseKind.DONE, round, endsAt, endsAt)
    }

    /** 一段节奏里开过几组（开了头就算一组） */
    private fun roundsIn(r: Retune, segEnd: Long): Int {
        val cycle = (r.workMin + r.restMin) * 60_000L
        val span = (segEnd - r.at).coerceAtLeast(0)
        return ((span + cycle - 1) / cycle).toInt()
    }

    private fun roundsBefore(t: Long): Int {
        val starts = listOf(Retune(startedAt, plan.workMin, plan.restMin)) + retunes.sortedBy { it.at }
        return starts.withIndex().sumOf { (i, r) -> roundsIn(r, minOf(starts.getOrNull(i + 1)?.at ?: t, t)) }
    }

    val doneRounds: Int get() = marks.count { it.done }
}

object FocusEngine {

    /** 提前结束要在一手里等这么久（离开页面重新计时）：比坚持下去更难受，才不会顺手就退 */
    const val QUIT_WAIT_MS = 3 * 60_000L

    fun start(plan: FocusPlan, now: Long): FocusSession? {
        if (FocusPlans.check(plan) != null) return null
        return FocusSession(plan, now, now + plan.totalMin * 60_000L)
    }

    /**
     * 中途换节奏（比如发现 6 分钟一组更顺）。总时长不变，只能延长；新节奏从现在开始算第一组。
     * 返回 null 表示这个节奏不合格。
     */
    fun retune(s: FocusSession, workMin: Int, restMin: Int, note: String, now: Long): FocusSession? {
        if (!s.active(now)) return null
        if (workMin !in FocusPlans.MIN_WORK..FocusPlans.MAX_WORK || restMin !in 0..FocusPlans.MAX_REST) return null
        return s.copy(retunes = s.retunes + Retune(now, workMin, restMin, note.trim()))
    }

    /** 往后延（只能延，不能缩） */
    fun extend(s: FocusSession, minutes: Int, now: Long): FocusSession? {
        if (!s.active(now) || minutes <= 0) return null
        val newEnd = s.endsAt + minutes * 60_000L
        if (newEnd - s.startedAt > FocusPlans.MAX_TOTAL * 60_000L) return null
        return s.copy(endsAt = newEnd)
    }

    /** 这一组结束时点一下（同一组只记一次，后点的覆盖） */
    fun mark(s: FocusSession, round: Int, done: Boolean, note: String, now: Long): FocusSession =
        s.copy(marks = s.marks.filter { it.round != round } + RoundMark(round, now, done, note.trim()))

    /** 点“提前结束”：开始等 */
    fun askQuit(s: FocusSession, now: Long): FocusSession = if (s.quitAskedAt != null) s else s.copy(quitAskedAt = now)

    /** 离开了等待页 / 反悔了：重新计时 */
    fun cancelQuit(s: FocusSession): FocusSession = s.copy(quitAskedAt = null)

    fun quitRemainingMs(s: FocusSession, now: Long): Long? = s.quitAskedAt?.let { (it + QUIT_WAIT_MS - now).coerceAtLeast(0) }

    /** 等满了才真的结束 */
    fun quit(s: FocusSession, now: Long): FocusSession? {
        val left = quitRemainingMs(s, now) ?: return null
        if (left > 0) return null
        return s.copy(endedAt = now)
    }
}

/**
 * 专注流开着时，切到一个应用怎么办。它排在收费站和陪练窗口前面：
 * 开着专注流，收费站的放行不算数，休息时也不放娱乐应用。
 */
object FocusRules {
    enum class Decision { ALLOW, BLOCK }

    /** 常驻放行的通讯应用：消息不能误，回完就回来（用户可以在设置里改） */
    val DEFAULT_MESSAGING = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "com.android.mms", "com.google.android.apps.messaging",
        "com.android.contacts", "com.vivo.message", "com.android.messaging",
    )

    fun decide(
        pkg: String,
        self: String,
        allowed: Set<String>,
        messaging: Set<String>,
        systemAllowed: Set<String>,
        staticSystem: Set<String>,
    ): Decision = when {
        pkg == self -> Decision.ALLOW
        pkg in systemAllowed || pkg in staticSystem -> Decision.ALLOW
        pkg in allowed -> Decision.ALLOW
        pkg in messaging -> Decision.ALLOW
        else -> Decision.BLOCK
    }

    /** 模板里写的应用名对上手机上装的应用：名字包含或被包含就算（“GPT”能对上“ChatGPT”） */
    fun resolve(names: List<String>, installed: Map<String, String>): Map<String, String?> = names.associateWith { n ->
        val k = Words.norm(n)
        installed.entries.firstOrNull { Words.norm(it.value) == k }?.key
            ?: installed.entries.firstOrNull { val v = Words.norm(it.value); k.length >= 2 && v.length >= 2 && (v.contains(k) || k.contains(v)) }?.key
    }
}
