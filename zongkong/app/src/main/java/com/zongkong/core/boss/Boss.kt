package com.zongkong.core.boss

import com.zongkong.core.hq.HqSession
import com.zongkong.core.work.Event
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 总控者的档案：它记下的陈述、约定、要回访的事、它自己加的严格程度。
 * 存在手机上（不在模型里）：换了模型，档案和宪章都还在。
 */

/** 对一条陈述的判定。 */
@Serializable
enum class ClaimVerdict(val label: String) {
    PENDING("待查"),
    VERIFIED("属实"),
    CONTRADICTED("对不上"),
    UNVERIFIABLE("无法核实"),
    VAGUE("说不清"),
}

/** 用户说的一件事（“我做了 5 小时饭”），以及总控查证后的判定。 */
@Serializable
data class Claim(
    val id: String,
    val at: Long,
    val text: String,
    /** 说的是哪段时间（0 = 没有）。 */
    val from: Long = 0,
    val to: Long = 0,
    val verdict: ClaimVerdict = ClaimVerdict.PENDING,
    /** 判定依据：时间线里的数字、截图、照片、一手记录。 */
    val evidence: String = "",
    val judgedAt: Long = 0,
)

@Serializable
enum class CommitStatus(val label: String) {
    OPEN("进行中"),
    KEPT("做到了"),
    BROKEN("没做到"),
    EXCUSED("免了"),
}

/** 一条约定：做什么、几点前、怎么检查。到点总控自己回头查。 */
@Serializable
data class Commitment(
    val id: String,
    val at: Long,
    val what: String,
    val due: Long,
    val check: String = "",
    val status: CommitStatus = CommitStatus.OPEN,
    val note: String = "",
    val closedAt: Long = 0,
)

/** 总控给自己定的一次回访：到点主动找你，问 / 查 [about]。 */
@Serializable
data class Check(
    val id: String,
    val at: Long,
    val due: Long,
    val about: String,
    val done: Boolean = false,
)

/** 总控的态度。信用决定下限；总控可以自己再收紧一段时间，不能放松。 */
@Serializable
enum class Stance(val label: String, val rule: String) {
    TRUST("信任", "说法基本采信；只在和记录明显冲突时追问。"),
    NORMAL("正常", "涉及时间、完成、放宽的说法，先对一眼记录再认。"),
    WARY("审慎", "每个说法都要证据（时间线、截图、照片、产出）；没有证据一律记“无法核实”，按规矩办。"),
    STRICT("严格", "不接受任何口头说法；语气冷、短；主动加约束；不给豁免，紧急放行关闭。"),
}

/** 总控自己加的严格程度（比信用算出来的更严），到点自动恢复。 */
@Serializable
data class Escalation(val stance: Stance, val until: Long, val reason: String)

/** 总控主动问了你一句（推送了通知）。[answeredAt] = 你回话的时刻，0 = 还没回。 */
@Serializable
data class Ask(val at: Long, val kind: String, val answeredAt: Long = 0)

@Serializable
data class Dossier(
    val claims: List<Claim> = emptyList(),
    val commitments: List<Commitment> = emptyList(),
    val checks: List<Check> = emptyList(),
    val escalation: Escalation? = null,
    val asks: List<Ask> = emptyList(),
)

/** 信用分的一项：为什么加、为什么扣。 */
data class CredItem(val at: Long, val delta: Int, val why: String)

object Boss {
    const val BASE = 70
    const val WINDOW_DAYS = 14
    /** 约定过期多久还没结论，就按“没做到”算。 */
    const val GRACE = 24 * 3600_000L
    /** 七天内最多免几次约定。 */
    const val EXCUSES_PER_WEEK = 1
    /** 主动问了多久不回，算“没回”。 */
    const val ASK_GRACE = 2 * 3600_000L
    /** 到点提醒后多久没有任何动静（开始 / 推迟 / 卡住 / 做完），算“提醒了没动”。 */
    const val REMIND_GRACE = 45 * 60_000L

    // ---------- 信用 ----------

    /**
     * 最近 14 天的信用（0–100，起点 70）：
     * 说法属实 +3、对不上 −12、说不清 −4、无法核实 −1；约定做到 +4、没做到 −10；
     * 总控时间做完 +3、提前退出 −5；
     * 沉默也有代价：主动问你 2 小时不回 −3；到点提醒后 45 分钟没有任何动静 −2（一天最多扣 3 次）；
     * 记下做完一步 +1（一天最多 4 次）。
     */
    fun ledger(d: Dossier, sessions: List<HqSession>, now: Long, events: List<Event> = emptyList()): List<CredItem> {
        val since = now - WINDOW_DAYS * 86_400_000L
        val out = mutableListOf<CredItem>()
        d.claims.filter { it.judgedAt >= since }.forEach { c ->
            val delta = when (c.verdict) {
                ClaimVerdict.VERIFIED -> 3
                ClaimVerdict.CONTRADICTED -> -12
                ClaimVerdict.VAGUE -> -4
                ClaimVerdict.UNVERIFIABLE -> -1
                ClaimVerdict.PENDING -> 0
            }
            if (delta != 0) out += CredItem(c.judgedAt, delta, "说法${c.verdict.label}：${c.text.take(30)}")
        }
        d.commitments.filter { it.closedAt >= since }.forEach { c ->
            val delta = when (c.status) {
                CommitStatus.KEPT -> 4
                CommitStatus.BROKEN -> -10
                else -> 0
            }
            if (delta != 0) out += CredItem(c.closedAt, delta, "约定${c.status.label}：${c.what.take(30)}")
        }
        sessions.filter { !it.active && it.endedAt >= since }.forEach { s ->
            out += if (s.early) CredItem(s.endedAt, -5, "总控时间提前退出：${s.earlyReason.take(30)}") else CredItem(s.endedAt, 3, "总控时间做完")
        }
        d.asks.forEach { a ->
            val late = a.at + ASK_GRACE
            val unanswered = if (a.answeredAt == 0L) now >= late else a.answeredAt > late
            if (unanswered && late >= since && late <= now) out += CredItem(late, -3, "问了没回（${a.kind}）")
        }
        val moves = events.filter { it.type == "start" || it.type == "snooze" || it.type == "reschedule" || it.type == "cancel" || it.type.startsWith("done:") || it.type.startsWith("stuck:") }
        events.filter { it.type == "remind" }.mapNotNull { r ->
            val late = r.at + REMIND_GRACE
            if (late < since || late > now) return@mapNotNull null
            val moved = moves.any { it.actionKey == r.actionKey && it.at in r.at..late }
            if (moved) null else late
        }.groupBy { day(it) }.values.forEach { day -> day.sorted().take(3).forEach { out += CredItem(it, -2, "提醒了没动") } }
        events.filter { it.type == "done:YES" && it.at >= since && it.at <= now }
            .groupBy { day(it.at) }.values.forEach { day -> day.sortedBy { it.at }.take(4).forEach { out += CredItem(it.at, 1, "做完一步") } }
        return out.sortedBy { it.at }
    }

    /** 按北京时间分天（只用来给“一天最多几次”分组）。 */
    private fun day(t: Long): Long = (t + 8 * 3600_000L) / 86_400_000L

    /** 记一次主动询问（只留最近 200 条）。 */
    fun asked(d: Dossier, kind: String, now: Long): Dossier = d.copy(asks = (d.asks + Ask(now, kind)).takeLast(200))

    /** 你回话了：之前没回的询问都算回了。 */
    fun answered(d: Dossier, now: Long): Dossier {
        if (d.asks.none { it.answeredAt == 0L }) return d
        return d.copy(asks = d.asks.map { if (it.answeredAt == 0L && it.at <= now) it.copy(answeredAt = now) else it })
    }

    fun credibility(d: Dossier, sessions: List<HqSession>, now: Long, events: List<Event> = emptyList()): Int =
        (BASE + ledger(d, sessions, now, events).sumOf { it.delta }).coerceIn(0, 100)

    fun stanceOf(credibility: Int): Stance = when {
        credibility >= 80 -> Stance.TRUST
        credibility >= 60 -> Stance.NORMAL
        credibility >= 40 -> Stance.WARY
        else -> Stance.STRICT
    }

    /** 现在的态度：信用算出来的，和总控自己加的严格程度，取更严的。 */
    fun stance(d: Dossier, sessions: List<HqSession>, now: Long, events: List<Event> = emptyList()): Stance {
        val base = stanceOf(credibility(d, sessions, now, events))
        val esc = d.escalation?.takeIf { it.until > now }?.stance ?: return base
        return if (esc.ordinal > base.ordinal) esc else base
    }

    // ---------- 陈述 ----------

    fun addClaim(d: Dossier, id: String, text: String, from: Long, to: Long, verdict: ClaimVerdict, evidence: String, now: Long): Dossier {
        val c = Claim(id, now, text.trim(), from, to, verdict, evidence.trim(), if (verdict == ClaimVerdict.PENDING) 0 else now)
        return d.copy(claims = (d.claims + c).takeLast(200))
    }

    /**
     * 判定一条陈述。规矩写在代码里，模型绕不过去：
     * 判“属实”必须写依据；已经判过的不能改成更好的结论（对不上 → 属实不行），只能改得更差。
     */
    fun judge(d: Dossier, id: String, verdict: ClaimVerdict, evidence: String, now: Long): Result<Dossier> {
        val c = d.claims.firstOrNull { it.id == id } ?: return Result.failure(IllegalArgumentException("没有这条陈述：$id"))
        if (verdict == ClaimVerdict.PENDING) return Result.failure(IllegalArgumentException("判定不能是“待查”"))
        if (verdict == ClaimVerdict.VERIFIED && evidence.trim().length < 6) return Result.failure(IllegalArgumentException("判“属实”要写依据（时间线数字、截图、照片、产出）"))
        if (c.verdict != ClaimVerdict.PENDING && rank(verdict) > rank(c.verdict)) {
            return Result.failure(IllegalArgumentException("已经判为“${c.verdict.label}”，不能改成更好的结论"))
        }
        val next = c.copy(verdict = verdict, evidence = listOf(c.evidence, evidence.trim()).filter { it.isNotBlank() }.joinToString("；"), judgedAt = now)
        return Result.success(d.copy(claims = d.claims.map { if (it.id == id) next else it }))
    }

    /** 结论好坏：属实 > 无法核实 > 说不清 > 对不上。 */
    private fun rank(v: ClaimVerdict) = when (v) {
        ClaimVerdict.VERIFIED -> 3
        ClaimVerdict.UNVERIFIABLE -> 2
        ClaimVerdict.VAGUE -> 1
        ClaimVerdict.CONTRADICTED -> 0
        ClaimVerdict.PENDING -> 4
    }

    // ---------- 约定 ----------

    /** 记一条约定，同时在到点后 5 分钟排一次回访。 */
    fun commit(d: Dossier, id: String, what: String, due: Long, check: String, now: Long): Dossier {
        val c = Commitment(id, now, what.trim(), due, check.trim())
        val k = Check("k$id", now, due + 5 * 60_000L, "约定到点：「${c.what}」${if (c.check.isNotBlank()) "，检查方式：${c.check}" else ""}。去查证据，再找用户对账（约定编号 $id）。")
        return d.copy(commitments = (d.commitments + c).takeLast(200), checks = (d.checks + k).takeLast(200))
    }

    /**
     * 给约定下结论。免掉（EXCUSED）要写清理由、七天最多一次、严格态度下不给免。
     */
    fun resolve(d: Dossier, id: String, status: CommitStatus, note: String, stance: Stance, now: Long): Result<Dossier> {
        val c = d.commitments.firstOrNull { it.id == id } ?: return Result.failure(IllegalArgumentException("没有这条约定：$id"))
        if (c.status != CommitStatus.OPEN) return Result.failure(IllegalArgumentException("这条约定已经是“${c.status.label}”了，不能再改"))
        if (status == CommitStatus.OPEN) return Result.failure(IllegalArgumentException("结论不能是“进行中”"))
        if (status == CommitStatus.EXCUSED) {
            if (stance == Stance.STRICT) return Result.failure(IllegalArgumentException("现在是严格态度，不给免"))
            if (note.trim().length < 10) return Result.failure(IllegalArgumentException("免掉要写清楚为什么（至少 10 个字），而且要有证据"))
            val recent = d.commitments.count { it.status == CommitStatus.EXCUSED && it.closedAt > now - 7 * 86_400_000L }
            if (recent >= EXCUSES_PER_WEEK) return Result.failure(IllegalArgumentException("七天内已经免过 $recent 次，不能再免"))
        }
        if (status == CommitStatus.KEPT && note.trim().length < 4) return Result.failure(IllegalArgumentException("判“做到了”要写依据"))
        val next = c.copy(status = status, note = note.trim(), closedAt = now)
        return Result.success(d.copy(commitments = d.commitments.map { if (it.id == id) next else it }))
    }

    /** 过期 24 小时还没结论的约定，按“没做到”算（不回应不等于做到了）。 */
    fun sweep(d: Dossier, now: Long): Dossier {
        if (d.commitments.none { it.status == CommitStatus.OPEN && now > it.due + GRACE }) return d
        return d.copy(commitments = d.commitments.map {
            if (it.status == CommitStatus.OPEN && now > it.due + GRACE) it.copy(status = CommitStatus.BROKEN, note = "过期 24 小时没有回应，按没做到算", closedAt = now) else it
        })
    }

    // ---------- 回访 ----------

    fun schedule(d: Dossier, id: String, due: Long, about: String, now: Long): Dossier =
        d.copy(checks = (d.checks + Check(id, now, due, about.trim())).takeLast(200))

    /** 到点了还没做的回访（最早的在前）。 */
    fun dueChecks(d: Dossier, now: Long): List<Check> = d.checks.filter { !it.done && it.due <= now }.sortedBy { it.due }

    fun markDone(d: Dossier, id: String): Dossier = d.copy(checks = d.checks.map { if (it.id == id) it.copy(done = true) else it })

    /** 总控自己收紧态度一段时间。只能更严，不能借这个放松。 */
    fun escalate(d: Dossier, to: Stance, hours: Int, reason: String, current: Stance, now: Long): Result<Dossier> {
        if (to.ordinal <= current.ordinal) return Result.failure(IllegalArgumentException("现在已经是“${current.label}”，只能往更严的方向调"))
        if (reason.trim().length < 4) return Result.failure(IllegalArgumentException("要写为什么"))
        val until = now + hours.coerceIn(1, 7 * 24) * 3600_000L
        return Result.success(d.copy(escalation = Escalation(to, until, reason.trim())))
    }

    // ---------- 给模型看的 ----------

    fun brief(d: Dossier, sessions: List<HqSession>, now: Long, zone: ZoneId, events: List<Event> = emptyList()): String = buildString {
        val cred = credibility(d, sessions, now, events)
        val st = stance(d, sessions, now, events)
        append("信用 $cred / 100 → 态度：${st.label}。${st.rule}\n")
        d.escalation?.takeIf { it.until > now }?.let { append("（你自己收紧到“${it.stance.label}”，到 ${hm(it.until, zone)}：${it.reason}）\n") }
        val open = d.commitments.filter { it.status == CommitStatus.OPEN }.sortedBy { it.due }
        if (open.isNotEmpty()) {
            append("未结的约定：\n")
            open.take(6).forEach { append("· [${it.id}] ${it.what}｜${if (it.due < now) "已过期" else "截止"} ${hm(it.due, zone)}${if (it.check.isNotBlank()) "｜检查：${it.check}" else ""}\n") }
        }
        val pending = d.checks.filter { !it.done && it.due > now }.sortedBy { it.due }
        if (pending.isNotEmpty()) append("你排了的回访：").append(pending.take(4).joinToString("；") { "${hm(it.due, zone)} ${it.about.take(30)}" }).append('\n')
        val recent = d.claims.takeLast(5)
        if (recent.isNotEmpty()) {
            append("最近的陈述：\n")
            recent.forEach { append("· [${it.id}] ${it.text.take(40)}｜${it.verdict.label}${if (it.evidence.isNotBlank()) "｜依据：${it.evidence.take(60)}" else ""}\n") }
        }
        val broken = d.commitments.count { it.status == CommitStatus.BROKEN && it.closedAt > now - 7 * 86_400_000L }
        val lies = d.claims.count { it.verdict == ClaimVerdict.CONTRADICTED && it.judgedAt > now - 7 * 86_400_000L }
        if (broken + lies > 0) append("近 7 天：没做到的约定 $broken 条，对不上的说法 $lies 条。\n")
        val silent = ledger(d, sessions, now, events).filter { it.at > now - 86_400_000L && (it.why.startsWith("问了没回") || it.why == "提醒了没动") }
        if (silent.isNotEmpty()) append("近 24 小时沉默：${silent.groupingBy { it.why.substringBefore("（") }.eachCount().entries.joinToString("，") { "${it.key} ${it.value} 次" }}（已经扣了信用）。\n")
    }

    private fun hm(t: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        return "%d/%d %02d:%02d".format(z.monthValue, z.dayOfMonth, z.hour, z.minute)
    }

    // ---------- 时间说法 ----------

    /**
     * “14:00”“昨天 22:30”“3小时前”“现在” → 时刻。今天的时间晚于现在，算昨天的。认不出返回 null。
     */
    fun clock(text: String, now: Long, zone: ZoneId): Long? {
        val t = text.trim().replace('：', ':')
        if (t.isEmpty()) return null
        if (t == "现在" || t == "now") return now
        Regex("""(\d+(?:\.\d+)?)\s*(小时|分钟)前""").find(t)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return now - (if (m.groupValues[2] == "小时") n * 3600_000 else n * 60_000).toLong()
        }
        val m = Regex("""(\d{1,2}):(\d{2})""").find(t) ?: Regex("""(\d{1,2})点(半)?""").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        if (h < 12 && (t.contains("下午") || t.contains("晚上") || t.contains("傍晚"))) h += 12
        val min = if (m.groupValues[2] == "半") 30 else m.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val yesterday = t.contains("昨")
        var at = LocalDate.from(today).minusDays(if (yesterday) 1 else 0).atTime(LocalTime.of(h, min)).atZone(zone).toInstant().toEpochMilli()
        if (!yesterday && at > now + 60_000) at -= 86_400_000L
        return at
    }
}

/** 总控者的眼睛和主动程度。关掉、调少都算放宽，24 小时后生效。 */
@Serializable
data class BossConfig(
    /** 截图抽查：亮屏时不定时截一张（只存手机上，总控要看时才发给你自己的 AI 接口）。 */
    val shots: Boolean = false,
    /** 每小时大约几张。 */
    val shotsPerHour: Int = 2,
    /** 截图留几天。 */
    val keepDays: Int = 3,
    /** 每天抽查几次（总控自己看一眼时间线和截图，有问题才找你；要接 AI）。 */
    val spotChecks: Int = 3,
    /** 娱乐应用连续用多少分钟，总控主动找你（0 = 不管）。 */
    val bingeMinutes: Int = 40,
    /** 锁住模型：能用的 AI 接口 / 模型要换，24 小时后才生效（免得不清醒时换一个听话的）。 */
    val modelLock: Boolean = true,
    /** 桌面宠物：浮在所有应用上面的小家伙，点它说话。 */
    val pet: Boolean = true,
    /** 主动程度：0 安静（只在出事时找你）、1 标准（一天几次问候 + 抽查）、2 黏人（每一个半小时问一次）。 */
    val initiative: Int = 1,
    /** 桌面宠物的形象：OWL 夜枭、CAT 团子、FLOWER 小葵。换形象不算放宽，马上生效。 */
    val petKind: String = "OWL",
) {
    companion object {
        val INITIATIVE = listOf("安静", "标准", "黏人")

        /** 两份设置取更严的（看得多、查得勤、管得紧的那份）。 */
        fun stricter(old: BossConfig, new: BossConfig): BossConfig = new.copy(
            shots = old.shots || new.shots,
            shotsPerHour = when {
                old.shots && new.shots -> maxOf(old.shotsPerHour, new.shotsPerHour)
                new.shots -> new.shotsPerHour
                else -> old.shotsPerHour
            },
            spotChecks = maxOf(old.spotChecks, new.spotChecks),
            bingeMinutes = when {
                old.bingeMinutes == 0 -> new.bingeMinutes
                new.bingeMinutes == 0 -> old.bingeMinutes
                else -> minOf(old.bingeMinutes, new.bingeMinutes)
            },
            modelLock = old.modelLock || new.modelLock,
            pet = old.pet || new.pet,
            initiative = maxOf(old.initiative, new.initiative),
        )
    }

    fun looser(new: BossConfig): List<String> = buildList {
        if (shots && !new.shots) add("关掉截图抽查")
        if (new.shots && shots && new.shotsPerHour < shotsPerHour) add("截图抽查减到每小时 ${new.shotsPerHour} 张")
        if (new.spotChecks < spotChecks) add("抽查减到每天 ${new.spotChecks} 次")
        if (bingeMinutes > 0 && (new.bingeMinutes == 0 || new.bingeMinutes > bingeMinutes)) add(if (new.bingeMinutes == 0) "不再管连续娱乐" else "连续娱乐 ${new.bingeMinutes} 分钟才找你")
        if (modelLock && !new.modelLock) add("解开模型锁")
        if (pet && !new.pet) add("收起桌面宠物")
        if (new.initiative < initiative) add("主动程度调低到“${INITIATIVE.getOrElse(new.initiative) { "?" }}”")
    }
}
