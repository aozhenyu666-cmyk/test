package com.zongkong.core.plan

import com.zongkong.core.DayClock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

/*
 * 预案（试验）：把一天排成几段，每段写好“做什么”和“万一……就做 B”。
 *
 * 为什么要有它：说好 9 点开始，结果下雨了 / 起晚了 / 临时有事，计划一碰就散，一散就整天放飞。
 * 预案提前把“碰到情况怎么办”写好：情况变了不是不做了，是换成 B 方案做。
 *
 *   定稿之前随便改；定稿之后，改一段要写为什么（留痕）。
 *   每段四个节点总控会出声：开始前 5 分钟（准备）、开始（开工）、过半（速记：做到哪了）、结束（交结果）。
 *   一段没法按 A 做：选 B 方案，或者写一句为什么跳过——不能悄悄没了。
 *
 * 写法（参谋和你都用这一种，一行一段）：
 *   09:00-10:30 资料分析两套 | B: 下雨不去图书馆，在家做一套
 *   14:00-15:00 申论大作文 | B: 只写提纲
 */

@Serializable
enum class BlockState(val label: String) {
    WAIT("没开始"), ON("在做"), DONE("做完"), PLAN_B("走了 B 方案"), SKIPPED("跳过"),
    ;

    val closed: Boolean get() = this == DONE || this == PLAN_B || this == SKIPPED
}

/** 一段：几点到几点（一天里的分钟数）、做什么、B 方案。 */
@Serializable
data class Block(
    val id: String,
    val start: Int,
    val end: Int,
    val what: String,
    val planB: String = "",
    /** 挂着的那步行动（可空）。 */
    val actionKey: String = "",
    val state: BlockState = BlockState.WAIT,
    /** 速记：过程中随手记的几句。 */
    val notes: List<String> = emptyList(),
    /** 结束时交的结果 / 跳过的原因。 */
    val result: String = "",
    /** 已经出过声的节点（pre / start / mid / end）。 */
    val prompted: Set<String> = emptySet(),
) {
    val span: String get() = "${DayClock.hhmm(start)}-${DayClock.hhmm(end)}"
}

@Serializable
data class DayScript(
    val date: String,
    val blocks: List<Block> = emptyList(),
    /** 定稿时间（0 = 还是草稿）。 */
    val lockedAt: Long = 0,
    val by: String = "你",
    val revisions: List<Revision> = emptyList(),
) {
    val locked: Boolean get() = lockedAt > 0
    fun block(id: String) = blocks.firstOrNull { it.id == id }
}

object Playbook {
    /** 节点：什么时候出声、说什么。 */
    enum class Node(val key: String, val label: String) {
        PRE("pre", "准备"), START("start", "开工"), MID("mid", "速记"), END("end", "交结果"),
    }

    data class Prompt(val block: Block, val node: Node, val text: String)

    // ---------- 写法 ----------

    private val LINE = Regex("""^\s*[-·*]?\s*(\d{1,2})[:：.](\d{2})\s*[-~–—到至]\s*(\d{1,2})[:：.](\d{2})\s+(.+)$""")
    private val B_SPLIT = Regex("""\s*[|｜]\s*(?:[Bb]\s*[:：]|B方案\s*[:：]?|备选\s*[:：]?)?\s*""")

    /** 一行一段；认不出的行原样返回在第二个列表里。 */
    fun parse(text: String, idSeed: Long): Pair<List<Block>, List<String>> {
        val blocks = mutableListOf<Block>()
        val bad = mutableListOf<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            val m = LINE.matchEntire(line)
            if (m == null) { bad += line; return@forEachIndexed }
            val (h1, m1, h2, m2, rest) = m.destructured
            // 凌晨 4 点前的算当天深夜（逻辑日）
            val start = DayClock.rank(h1.toInt() * 60 + m1.toInt())
            var end = DayClock.rank(h2.toInt() * 60 + m2.toInt())
            if (end <= start) end += 24 * 60
            val parts = rest.split(B_SPLIT, limit = 2)
            val what = parts[0].trim()
            if (what.isEmpty()) { bad += line; return@forEachIndexed }
            blocks += Block("b" + idSeed.toString(36) + i, start, end, what, parts.getOrNull(1)?.trim().orEmpty())
        }
        return blocks.sortedBy { it.start } to bad
    }

    fun format(s: DayScript): String = s.blocks.joinToString("\n") { b -> b.span + " " + b.what + (if (b.planB.isNotBlank()) " | B: " + b.planB else "") }

    /** 两段时间重叠。 */
    fun overlaps(blocks: List<Block>): List<Pair<Block, Block>> =
        blocks.sortedBy { it.start }.zipWithNext().filter { (a, b) -> b.start < a.end }

    // ---------- 本子里放着 ----------

    fun of(book: PlanBook, date: String): DayScript? = book.scripts.lastOrNull { it.date == date }

    fun today(book: PlanBook, now: Long, zone: ZoneId): DayScript? = of(book, DayClock.dateKey(now, zone))

    fun put(book: PlanBook, s: DayScript): PlanBook = book.copy(scripts = (book.scripts.filterNot { it.date == s.date } + s).takeLast(30))

    /**
     * 写 / 改预案。草稿随便改；定稿后改要写原因（至少 4 个字），已经开始或结束的段保留原样。
     * 返回 (新的预案, 错误)。
     */
    fun write(old: DayScript?, date: String, blocks: List<Block>, why: String, by: String, now: Long): Pair<DayScript?, String?> {
        if (blocks.isEmpty()) return null to "一段都没认出来。写法：09:00-10:30 做什么 | B: 万一……就做什么"
        overlaps(blocks).firstOrNull()?.let { (a, b) -> return null to "「${a.what}」和「${b.what}」时间重叠了" }
        if (old == null || !old.locked) return DayScript(date, blocks, by = by, revisions = old?.revisions.orEmpty()) to null
        if (why.trim().length < 4) return null to "预案已经定稿，改要写一句为什么（至少 4 个字）"
        // 定稿后：已经动过的段不许改掉
        val touched = old.blocks.filter { it.state != BlockState.WAIT }
        val kept = touched + blocks.filter { nb -> touched.none { it.start == nb.start && it.what == nb.what } }
        overlaps(kept).firstOrNull()?.let { (a, b) -> return null to "「${a.what}」已经开始了，新排的「${b.what}」和它重叠" }
        return old.copy(blocks = kept.sortedBy { it.start }, revisions = old.revisions + Revision(now, "改了预案", why.trim())) to null
    }

    fun lock(s: DayScript, now: Long): DayScript = if (s.locked) s else s.copy(lockedAt = now)

    private fun update(s: DayScript, id: String, f: (Block) -> Block) = s.copy(blocks = s.blocks.map { if (it.id == id) f(it) else it })

    fun begin(s: DayScript, id: String): DayScript = update(s, id) { if (it.state == BlockState.WAIT) it.copy(state = BlockState.ON) else it }

    fun note(s: DayScript, id: String, text: String): DayScript =
        if (text.isBlank()) s else update(s, id) { it.copy(notes = (it.notes + text.trim()).takeLast(20)) }

    fun finish(s: DayScript, id: String, result: String): DayScript = update(s, id) { it.copy(state = BlockState.DONE, result = result.trim()) }

    /** 走 B 方案：这一段还算数，换成 B 做。 */
    fun planB(s: DayScript, id: String, why: String, now: Long): Pair<DayScript, String?> {
        val b = s.block(id) ?: return s to "没有这一段"
        if (b.planB.isBlank()) return s to "这一段没写 B 方案。要么按 A 做，要么写原因跳过。"
        return update(s, id) { it.copy(state = BlockState.PLAN_B, result = "B：${it.planB}" + (if (why.isNotBlank()) "（${why.trim()}）" else "")) }
            .let { it.copy(revisions = it.revisions + Revision(now, "「${b.what}」改走 B 方案", why.ifBlank { "没写" })) } to null
    }

    /** 跳过：要写原因。 */
    fun skip(s: DayScript, id: String, why: String, now: Long): Pair<DayScript, String?> {
        val b = s.block(id) ?: return s to "没有这一段"
        if (why.trim().length < 4) return s to "跳过要写一句为什么（至少 4 个字）"
        return update(s, id) { it.copy(state = BlockState.SKIPPED, result = why.trim()) }
            .let { it.copy(revisions = it.revisions + Revision(now, "跳过「${b.what}」", why.trim())) } to null
    }

    // ---------- 节点 ----------

    /** 逻辑日 [date] 的第 [minute] 分钟（可以超过 24 点，算到次日凌晨）。 */
    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli() + minute * 60_000L

    /** 某一段某个节点的时刻。 */
    fun nodeAt(s: DayScript, b: Block, node: Node, zone: ZoneId): Long {
        val d = LocalDate.parse(s.date)
        return when (node) {
            Node.PRE -> at(d, b.start - 5, zone)
            Node.START -> at(d, b.start, zone)
            Node.MID -> at(d, (b.start + b.end) / 2, zone)
            Node.END -> at(d, b.end, zone)
        }
    }

    /**
     * 现在该出声的节点（每段每个节点只出一次；过了 15 分钟还没出声的就不补了，免得一开机连响一串）。
     * 只有定稿的预案出声。
     */
    fun due(s: DayScript, now: Long, zone: ZoneId): List<Prompt> {
        if (!s.locked) return emptyList()
        val out = mutableListOf<Prompt>()
        for (b in s.blocks) {
            if (b.state.closed) continue
            for (n in Node.entries) {
                if (n.key in b.prompted) continue
                // 段短于 30 分钟不问过半；已经在做了就不再催开工
                if (n == Node.MID && b.end - b.start < 30) continue
                if ((n == Node.PRE || n == Node.START) && b.state == BlockState.ON) continue
                val t = nodeAt(s, b, n, zone)
                if (now >= t && now - t < 15 * 60_000L) out += Prompt(b, n, say(b, n))
            }
        }
        // 同一段同时到了好几个节点（比如刚定稿），只说最后一个
        return out.groupBy { it.block.id }.map { it.value.last() }
    }

    fun markPrompted(s: DayScript, id: String, node: Node): DayScript {
        // 说了后面的节点，前面的也算说过
        val keys = Node.entries.takeWhile { it != node }.map { it.key } + node.key
        return update(s, id) { it.copy(prompted = it.prompted + keys) }
    }

    fun say(b: Block, n: Node): String = when (n) {
        Node.PRE -> "5 分钟后：${b.what}。手上的收个尾。" + (if (b.planB.isNotBlank()) "情况不对就走 B：${b.planB}。" else "")
        Node.START -> "${DayClock.hhmm(b.start)} 了，开始${b.what}。"
        Node.MID -> "${b.what}过半了。速记一句：做到哪了？"
        Node.END -> "${b.what}到点了。做完没有？交一句结果。"
    }

    /** 下一个要出声的时刻（给系统闹钟）。 */
    fun nextNode(s: DayScript, now: Long, zone: ZoneId): Long? {
        if (!s.locked) return null
        return s.blocks.filter { !it.state.closed }.flatMap { b -> Node.entries.filter { it.key !in b.prompted }.map { nodeAt(s, b, it, zone) } }
            .filter { it > now }.minOrNull()
    }

    fun current(s: DayScript, now: Long, zone: ZoneId): Block? {
        val d = LocalDate.parse(s.date)
        return s.blocks.firstOrNull { it.state == BlockState.ON } ?: s.blocks.firstOrNull { !it.state.closed && now >= at(d, it.start, zone) && now < at(d, it.end, zone) }
    }

    fun next(s: DayScript, now: Long, zone: ZoneId): Block? {
        val d = LocalDate.parse(s.date)
        return s.blocks.filter { !it.state.closed && at(d, it.start, zone) > now }.minByOrNull { it.start }
    }

    /** 已经过了结束时间、还没交代的段。 */
    fun overdue(s: DayScript, now: Long, zone: ZoneId): List<Block> {
        val d = LocalDate.parse(s.date)
        return s.blocks.filter { !it.state.closed && at(d, it.end, zone) <= now }
    }

    /** 给参谋看的一段。 */
    fun brief(s: DayScript, now: Long, zone: ZoneId): String = buildString {
        append("今天的预案（").append(if (s.locked) "已定稿" else "草稿，还没定稿").append("）：\n")
        s.blocks.forEach { b ->
            append("· ${b.span} ${b.what}")
            if (b.planB.isNotBlank()) append("｜B：${b.planB}")
            append("｜${b.state.label}")
            if (b.notes.isNotEmpty()) append("｜速记：${b.notes.last().take(40)}")
            if (b.result.isNotBlank()) append("｜${b.result.take(40)}")
            append(" id=${b.id}\n")
        }
        current(s, now, zone)?.let { append("现在这段：${it.what}\n") }
        overdue(s, now, zone).takeIf { it.isNotEmpty() }?.let { l -> append("过了点还没交代：").append(l.joinToString("、") { it.what }).append('\n') }
    }
}
