package com.yishou.app.thread

import com.yishou.app.judge.Guard
import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Locks
import com.yishou.app.judge.Point
import com.yishou.app.judge.Verdict
import com.yishou.app.llm.Tagged
import com.yishou.app.sharp.Sharp
import com.yishou.app.think.Words

/**
 * 思维线程怎么往下走。全是纯逻辑：出题要问模型的地方，由调用方传 ask 进来（失败返回 null）。
 *
 * 为什么不会越聊越偏：
 * - 出题时模型只看得到目标、已定的结论、当前这一项和这一项上最近两手，看不到整段聊天，没法顺着你上一句往外扯；
 * - 每一问必须标明在推哪一项（本机核对），标错了就重出；
 * - 每一问都是锁题，交给判卷器判；连着两手没推进，下一问就是拉回。
 */
object StrandEngine {

    /** 连着这么多手没推进，就拉回 */
    const val STALL_LIMIT = 2
    /** 一项上接住这么多手，就该下结论了 */
    const val PASSES_TO_CLOSE = 2

    sealed interface Created {
        data class Ok(val strand: Strand) : Created
        data class Bad(val why: String) : Created
    }

    /**
     * 建线程。目标和“什么时候算定了”都要用户自己写：
     * 后一句不能是套话，也不能只是把目标换个说法——这是这条线程的第一手。
     */
    fun create(goal: String, doneWhen: String, items: List<String>, now: Long): Created {
        val g = goal.trim()
        val d = doneWhen.trim()
        if (Words.substance(g) < 6 || Sharp.hollow(g) != null) return Created.Bad("目标写具体一点：要定下来的到底是什么")
        if (Words.substance(d) < 6 || Sharp.hollow(d) != null) return Created.Bad("写清楚：做到什么程度，就算定下来了")
        if (Guard.novel(d, listOf(g)) < 4) return Created.Bad("“算定了”要说标准，不是把目标再说一遍")
        val list = items.map { it.trim() }.filter { Words.substance(it) >= 4 }.distinct()
        if (list.size !in 2..5) return Created.Bad("待定项要 2 到 5 个")
        return Created.Ok(
            Strand(goal = g, doneWhen = d, items = list.mapIndexed { i, q -> Item("I${i + 1}", q) }, createdAt = now, updatedAt = now),
        )
    }

    /** 下一问该是哪一种 */
    fun nextKind(s: Strand): MoveKind {
        if (s.done) error("线程已经定了")
        val cur = s.current ?: return MoveKind.FINAL
        if (s.stall >= STALL_LIMIT) return MoveKind.PULL_BACK
        if (cur.passes >= PASSES_TO_CLOSE) return MoveKind.CLOSE
        return MoveKind.STEP
    }

    /** 让模型提议几个待定项。用户可以改，改完再 create */
    suspend fun proposeItems(goal: String, ask: suspend (String, String) -> String?): List<String>? {
        val reply = ask(StrandPrompts.PLAN, "目标：" + goal.trim()) ?: return null
        return StrandPrompts.parseItems(reply).takeIf { it.size >= 2 }
    }

    /**
     * 出下一问。拉回和收尾是本机出的（不用等模型）；推进和下结论问模型，标错项或格式不对重出一次。
     * 返回 null：模型不在。这时入口换别的题或低能量路，不拿没锁的题凑数。
     */
    suspend fun serve(s: Strand, ask: suspend (String, String) -> String?, now: Long): Pending? {
        s.pending?.let { return it }
        return when (val kind = nextKind(s)) {
            MoveKind.PULL_BACK -> Pending(kind, s.current?.id, pullBackQ(s, now))
            MoveKind.FINAL -> Pending(kind, null, finalQ(s, now))
            else -> {
                val item = s.current ?: return null
                val user = StrandPrompts.serveUser(s, item, kind)
                repeat(2) {
                    val reply = ask(StrandPrompts.SERVE, user) ?: return@repeat
                    StrandPrompts.parseServe(reply, item.id, "s${s.id}-${item.id}-$now")?.let { return Pending(kind, item.id, it) }
                }
                null
            }
        }
    }

    /** 跑偏了：要么把刚才的话和这一项连上，要么老实回到这一项说一个具体想法 */
    fun pullBackQ(s: Strand, now: Long): LockedQ {
        val item = s.current!!
        val last = s.moves.lastOrNull()?.answer.orEmpty().take(60)
        val prompt = buildString {
            append("我们在定：${s.goal}\n现在卡在：${item.question}\n")
            if (last.isNotBlank()) append("你刚才说的是：“$last”\n")
            append("这和“${item.question}”有什么关系？用一句话连上；连不上，就直接对这一项说一个具体想法。")
        }
        return LockedQ(
            id = "s${s.id}-pull-$now",
            prompt = prompt,
            points = listOf(Point("P1", "说出了和「${item.question}」直接相关的具体内容：要么讲清刚才的话怎样影响这一项，要么直接对这一项给出一个具体想法（事实、数字、选项或取舍），不是泛泛表态")),
            need = 1,
            reference = "",
        )
    }

    /** 所有项都定了：回答目标，按自己定的标准判 */
    fun finalQ(s: Strand, now: Long): LockedQ {
        val prompt = buildString {
            append("目标：${s.goal}\n")
            append("已经定下的：\n")
            s.settled.forEach { append("· ${it.question}：${it.conclusion}\n") }
            append("你说过“${s.doneWhen}”就算定了。现在给出答案。")
        }
        return LockedQ(
            id = "s${s.id}-final-$now",
            prompt = prompt,
            points = listOf(
                Point("P1", "对「${s.goal}」给出了明确的答案（具体的时间、选择或数值），不是“看情况”“再说”"),
                Point("P2", "答案满足用户自己定的标准：${s.doneWhen}"),
            ),
            need = 2,
            reference = "",
        )
    }

    /**
     * 交了一手以后线程怎么变。判卷不在就原样不动（那一问还挂着）。
     * conclusion：EXTERNAL 时传外部总结里的“结论”那一行。
     */
    fun apply(s: Strand, answer: String, v: Verdict, now: Long, conclusion: String? = null): Strand {
        val p = s.pending ?: return s
        if (v.unavailable) return s
        val move = Move(now, p.itemId, p.kind, p.q.prompt, answer.trim(), v.passed, v.offTopic)
        val base = s.copy(moves = s.moves + move, pending = null, updatedAt = now)
        fun upd(f: (Item) -> Item) = base.items.map { if (it.id == p.itemId) f(it) else it }
        return when (p.kind) {
            MoveKind.STEP ->
                if (v.passed) base.copy(items = upd { it.copy(passes = it.passes + 1) }, stall = 0)
                else base.copy(stall = base.stall + 1)
            MoveKind.CLOSE ->
                if (v.passed) base.copy(items = upd { it.copy(state = ItemState.SETTLED, conclusion = answer.trim()) }, stall = 0)
                else base.copy(stall = base.stall + 1)
            // 拉回没过就还是拉回：要么连上，要么另开线程
            MoveKind.PULL_BACK -> if (v.passed) base.copy(stall = 0) else base
            // 外部带回来的不直接算定：下一问要你自己用自己的话下结论（撤梯）
            MoveKind.EXTERNAL ->
                if (v.passed) base.copy(items = upd { it.copy(passes = maxOf(it.passes, PASSES_TO_CLOSE)) }, stall = 0)
                else base
            MoveKind.FINAL -> if (v.passed) base.copy(done = true, answer = answer.trim()) else base
        }
    }

    /** 把外部总结挂成当前这一问（会替换掉还没答的那一问） */
    fun startReturn(s: Strand, now: Long): Strand {
        val item = s.current ?: return s
        return s.copy(pending = Pending(MoveKind.EXTERNAL, item.id, Handoff.returnQ(s, item, now)), updatedAt = now)
    }

    /** 拉回时选“另开一个线程”：刚才那句话当新线程的目标草稿，旧线程的拉回清掉 */
    fun splitOff(s: Strand, now: Long): Pair<Strand, String> {
        val draft = s.moves.lastOrNull()?.answer.orEmpty()
        return s.copy(stall = 0, pending = null, updatedAt = now) to draft
    }
}

object StrandPrompts {

    val PLAN = """
用户要定下一件事。列出要定下它必须先回答的 3 到 5 个问题（待定项）。
每个问题要具体、能回答出事实或取舍，不要“综合考虑各方面”这种。按先后顺序排。
只按这个格式回，每行一个：
[I1] 问题
[I2] 问题
""".trim()

    private val ITEM = Regex("^\\s*[\\[【]\\s*I\\s*(\\d{1,2})\\s*[\\]】]\\s*[:：]?\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun parseItems(text: String): List<String> = text.lines().mapNotNull { ITEM.find(it.trim())?.groupValues?.get(2)?.trim() }
        .filter { Words.substance(it) >= 4 }.distinct().take(5)

    val SERVE = """
你是陪人把一件事想定的搭档。你只负责出下一问，不聊天、不总结、不夸。

规则：
1. 只围绕给你的那一个待定项出题，不能顺着用户上一句话展开别的话题。用户上一句要是跑偏了，不要接。
2. 推进：问一个能逼出事实、数字或取舍的具体问题，让这一项往“定下来”走一步。不要问“你怎么看”。
3. 下结论：让用户给这一项下结论，并说出依据。
4. 列 2 到 3 个要点：一个好回答必须说到的东西，写成判据，再写一句只指方向、不透露判据的提示。判据里的说法不能在题目里直接出现。

只按这个格式回：
【项】I2
【题目】……
【要点】
1. 判据 | 提示
2. 判据 | 提示
【参考】一两句
""".trim()

    fun serveUser(s: Strand, item: Item, kind: MoveKind): String = buildString {
        appendLine("目标：${s.goal}")
        appendLine("算定了的标准：${s.doneWhen}")
        if (s.settled.isNotEmpty()) {
            appendLine("已经定下：")
            s.settled.forEach { appendLine("· ${it.question}：${it.conclusion}") }
        }
        appendLine("当前待定项：[${item.id}] ${item.question}")
        val recent = s.moves.filter { it.itemId == item.id && it.passed }.takeLast(2)
        if (recent.isNotEmpty()) {
            appendLine("这一项上已经接住的：")
            recent.forEach { appendLine("· 问：${it.ask.take(80)} 答：${it.answer.take(120)}") }
        }
        append(if (kind == MoveKind.CLOSE) "这一问：让用户给 [${item.id}] 下结论并说依据。" else "这一问：推进 [${item.id}]。")
    }

    /** 读模型出的题：标的项不对、题不合格都返回 null */
    fun parseServe(text: String, itemId: String, qid: String): LockedQ? {
        val tag = Tagged.sections(text, setOf("项", "题目", "要点", "参考"))["项"]?.joinToString("")?.trim()?.uppercase()?.replace(" ", "")
        if (tag != itemId.uppercase()) return null
        return Locks.parseGen(qid, text)
    }
}
