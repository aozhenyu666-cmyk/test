package com.zongkong.core.agent

import com.zongkong.core.DayClock
import com.zongkong.core.plan.Aims
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Rec
import com.zongkong.core.work.Work
import java.time.ZoneId

/**
 * 已经问过的记录：缺口键 → 上次问的时刻。
 *
 * 放在文件顶层而不是 [Inquiry] 里面：Kotlin 不支持嵌套的类型别名。
 * 由 App 层持久化（存成 `键|时刻` 的行），这里只做纯逻辑：该不该再问。
 */
typealias AskedGaps = Map<String, Long>

/**
 * 追问：把「它还缺什么」变成一句能回答的问题。
 *
 * # 为什么需要这个
 *
 * 用户的原话是：「我只给它很少信息，它也只能按这些用」——它不是不会推断，是**不追**。
 * 之前它的「问」只有两种：末尾给一排【选项】按钮（那是让他点，不是问他），
 * 和宪章里「模糊就追问」这条原则（原则管不住模型，模型总是倾向于直接给答案）。
 *
 * 所以要给它一套**结构**：先诊断出缺口，再排优先级，再翻成一句具体的话。
 * 判断留给模型，缺哪个口子、先补哪个，是代码算得出来的。
 *
 * # 三条硬规矩
 *
 * 1. **一次只问一个**。[Gap] 列表是排好序的，[plan] 只取第一个；模型一次抛三个问题，
 *    用户一个都不会答。
 * 2. **问过的不要重复问**。每个缺口有稳定的 [Gap.key]；问过之后写进 [Asked]，
 *    冷却期内不再问，除非用户主动提到或者数据变了。同一个问题问两遍，等于没问。
 * 3. **能从记录里读到的，不要问用户**。诊断时先查数据；数据里有答案的缺口根本不进列表。
 *    这是「自主性」的一半：先自己找，找不到才问。
 *
 * # 和宪章的关系
 *
 * 宪章第 3 条说「模糊就追问，一次只问一个问题」——那是**审问**（核实说法）。
 * 这里是**立项式追问**（把事情问清楚）：为什么做、做到什么算完、卡在哪、什么时候做。
 * 两者共用本文件的冷却机制，免得同一个问题又审又问。
 */
object Inquiry {

    /** 一个缺口：还可以问的一句话，加上为什么现在问它。 */
    data class Gap(
        /** 稳定键，用来记住「已经问过」。同一件事同一个口子，键必须一样。 */
        val key: String,
        /** 属于哪一类（见 [Kind]），用来在提示词里说清这一问的目的。 */
        val kind: Kind,
        /** 这一问冲着什么：事项标题、目标标题、行动标题；没有具体对象就空。 */
        val subject: String = "",
        /** 直接能问出口的一句话。 */
        val ask: String,
        /** 为什么现在问这个——写进提示词，让模型知道这一问的目的，不要跑偏。 */
        val why: String,
        /** 用户答完之后，该落到哪个字段（[F] 里的名字，或 set_aim / add_action 这类动作名）。 */
        val landsIn: String = "",
        /** 优先级：数字小的先问。 */
        val priority: Int,
    )

    /**
     * 缺口的种类。
     *
     * 顺序就是「该先问什么」的大方向：先问今天怎么动（不然一天又过了），
     * 再问这件事本身立不立得住，最后才是远处的目标。
     */
    enum class Kind(val label: String) {
        /** 今天没有主线 / 没有一步排进时间：最急，一天不做事就没了。 */
        TODAY("今天的行动"),

        /** 事项很薄：为什么做、做到什么算完没写。建完就散的头号原因。 */
        PROJECT("这件事的底子"),

        /** 有下一步但没排时间：想法停在脑子里。 */
        UNSCHEDULED("没排时间的下一步"),

        /** 卡住的行动没写卡在哪：问不出卡点，就永远卡着。 */
        STUCK("卡在哪"),

        /** 长期目标没有里程碑 / 没有期限：远处的东西不落地就只是愿望。 */
        AIM("远方的落点"),

        /** 计划外的投入明显多于计划内：问他这周实际在干什么。 */
        DRIFT("偏到哪儿去了"),
    }

    /** 一次追问的决定：问什么、怎么问、为什么是它。 */
    data class Plan(
        /** 要问的那一个缺口；没有缺口就是 null。 */
        val gap: Gap?,
        /** 所有算出来的缺口（已排序，已排除冷却中的），给提示词当背景。 */
        val gaps: List<Gap> = emptyList(),
        /**
         * 冷却中被按住、这一轮不许问的缺口。
         *
         * 和「压根没有缺口」必须分开：这两种情况下提示词要说的完全不同——
         * 一种是「别为了问而问」，另一种是「问题还在，但刚问过，别重问」。
         */
        val cooling: List<Gap> = emptyList(),
    ) {
        val has: Boolean get() = gap != null

        /** 有没有任何缺口（含冷却中的）：决定提示词走哪一支。 */
        val anything: Boolean get() = gap != null || cooling.isNotEmpty()
    }

    /**
     * 同一个缺口问过之后，多久之内不再问。
     *
     * 今天的行动：4 小时（一天里可能问两三次，但不会每次开口都问）。
     * 其他的：24 小时（一天问一遍够了；问多了是唠叨，不是追问）。
     */
    fun cooldown(kind: Kind): Long = when (kind) {
        Kind.TODAY -> 4 * 3600_000L
        Kind.STUCK -> 6 * 3600_000L
        else -> 24 * 3600_000L
    }

    /**
     * 诊断 + 排序 + 挑一个问。
     *
     * @param asked 已经问过的键和时刻；冷却期内的不再问。
     * @param focus 用户刚提到的东西（事项标题、关键词）：和它相关的缺口提到最前面，
     *              因为「他正说到这件事」是最好的追问时机。
     */
    fun plan(
        env: AgentEnv,
        now: Long = env.now(),
        zone: ZoneId = env.zone,
        asked: AskedGaps = emptyMap(),
        focus: String = "",
    ): Plan {
        val all = diagnose(env, now, zone)
        val f = focus.trim()
        val (cold, warm) = all.partition { g -> cooling(g, asked, now) }
        val ranked = warm.sortedWith(
            compareBy<Gap>(
                // 用户刚提到的，先问——他正在想这件事
                { if (f.isNotBlank() && it.subject.contains(f, ignoreCase = true)) 0 else 1 },
                { it.priority },
            ),
        )
        return Plan(gap = ranked.firstOrNull(), gaps = ranked, cooling = cold)
    }

    /** 这个缺口是不是还在冷却期里（问过了，别追着问）。 */
    fun cooling(g: Gap, asked: AskedGaps, now: Long): Boolean {
        val last = asked[g.key] ?: return false
        return now - last < cooldown(g.kind)
    }

    /**
     * 算出所有缺口（不排序、不考虑冷却）。纯读，不改任何东西。
     *
     * 每一条都必须「记录里确实没有」才成立——能从数据里读出来的，不给用户添麻烦。
     */
    fun diagnose(env: AgentEnv, now: Long = env.now(), zone: ZoneId = env.zone): List<Gap> {
        val work = env.work()
        val book = env.plans()
        val out = mutableListOf<Gap>()
        val today = DayClock.dateKey(now, zone)

        // ---------- 1. 今天怎么动 ----------

        val plan = Plans.active(book, now, zone)
        val main = Plans.mainAction(book, work, now, zone)
        val openActions = work.actions().filter { it.actionStatus.open }
        val todayKeys = openActions.filter { a -> a.plan?.let { plannedToday(it, now, zone) } ?: false }

        if (main == null && openActions.isEmpty() && work.threads().none { it.threadStatus.open }) {
            // 什么都没记：问的不是“今天做什么”，是“现在手上有什么”，否则他答不出来
            out += Gap(
                key = "today:empty",
                kind = Kind.TODAY,
                ask = "现在手上最想推动的是哪件事？一句话就行，我替你拆成今天能做的一步。",
                why = "记录里连一件进行中的事都没有，问“今天做什么”他答不出来；先问“有什么”，才有得拆。",
                landsIn = "create_thread",
                priority = 10,
            )
        } else if (main == null) {
            out += Gap(
                key = "today:no-main",
                kind = Kind.TODAY,
                subject = openActions.firstOrNull()?.let { work.rec(it.threadKey)?.title ?: it.title }.orEmpty(),
                ask = if (todayKeys.isNotEmpty()) {
                    "今天排了 ${todayKeys.size} 步，主线还没定。今天最该先落地的是哪一步？"
                } else {
                    "今天还没有一步排进时间。现在最想先做的是哪件？我按你手上有的事挑一件排进去。"
                },
                why = "今天没有主线，等于一天没有落点；这一步不定下来，后面全是空谈。",
                landsIn = "set_day_main",
                priority = 20,
            )
        }

        // ---------- 2. 这件事的底子（很薄的事项） ----------

        Focus.thinThreads(work, now).take(3).forEach { t ->
            val missing = buildList {
                if (t[F.WHY].isBlank()) add(F.WHY)
                if (t[F.CRITERIA].isBlank()) add(F.CRITERIA)
            }
            if (missing.isNotEmpty()) {
                val field = missing.first()
                out += Gap(
                    key = "project:${t.key}:$field",
                    kind = Kind.PROJECT,
                    subject = t.title,
                    ask = when (field) {
                        F.WHY -> "「${t.title}」为什么要做？做成什么样你才算没白干？"
                        else -> "「${t.title}」做到什么算完？给我一个能检查的标准，我照着验收。"
                    },
                    why = "这件事建了但${if (field == F.WHY) "为什么做" else "完成依据"}没写，行动也不超过一步——建完就散，多半是从这儿开始的。",
                    landsIn = field,
                    priority = 30,
                )
            }
        }

        // ---------- 3. 有下一步但没排时间 ----------

        Focus.unplannedThreads(work).take(3).forEach { t ->
            out += Gap(
                key = "unscheduled:${t.key}",
                kind = Kind.UNSCHEDULED,
                subject = t.title,
                ask = "「${t.title}」的下一步是「${t[F.NEXT].take(40)}」，想放在哪天几点？",
                why = "下一步写在事项上但没排进时间，等于停在想法里；给个具体时刻就能动。",
                landsIn = "add_action",
                priority = 40,
            )
        }

        // ---------- 4. 卡住的没说卡在哪 ----------

        work.actions().filter { it.actionStatus == com.zongkong.core.work.ActionStatus.STUCK }.take(3).forEach { a ->
            val reason = a[F.STUCK].ifBlank { a[F.BREAK] }
            if (reason.isBlank()) {
                out += Gap(
                    key = "stuck:${a.key}",
                    kind = Kind.STUCK,
                    subject = a.title,
                    ask = "「${a.title}」卡在哪儿了？是不知道下一步做什么、缺东西，还是单纯不想开始？",
                    why = "这一步标了卡住但没写卡点。三种卡法要对三种办法，不问清楚只能猜。",
                    landsIn = F.STUCK,
                    priority = 35,
                )
            }
        }

        // ---------- 5. 远方没有落点 ----------

        Aims.open(book).forEach { a ->
            val left = Aims.daysLeft(a, now, zone)
            when {
                // 没有期限：问什么时候要
                left == null -> out += Gap(
                    key = "aim:${a.id}:deadline",
                    kind = Kind.AIM,
                    subject = a.title,
                    ask = "「${a.title}」大概什么时候要有个结果？给个日子，我好倒推。",
                    why = "长期目标没有期限，倒推不了，也就排不进任何一周。",
                    landsIn = "set_aim",
                    priority = 50,
                )
                // 有期限没有里程碑：问中间要交出什么
                a.milestones.isEmpty() -> out += Gap(
                    key = "aim:${a.id}:milestones",
                    kind = Kind.AIM,
                    subject = a.title,
                    ask = "「${a.title}」还剩 $left 天。往回倒，中间至少要交出哪一两样东西？",
                    why = "有截止日但没有里程碑，只剩最后几天的恐慌；中间节点能让它变成可排的几段。",
                    landsIn = "set_milestones",
                    priority = 55,
                )
            }
        }

        // ---------- 6. 计划外投入明显多于计划内 ----------

        if (plan != null) {
            val pr = Plans.progress(plan, work, now, zone)
            val planned = pr.planMinutes
            val off = pr.offMinutes
            // 只有「明显」才算：计划内为 0 或计划外超过计划内一倍，才值得问一句
            if (off >= 60 && (planned == 0 || off > planned * 2)) {
                out += Gap(
                    key = "drift:${plan.id}:${today.take(10)}",
                    kind = Kind.DRIFT,
                    ask = "这周计划内 $planned 分钟、计划外 $off 分钟。计划外那些事里，有没有一件其实该正式排进来？",
                    why = "计划外的量已经是计划内的两倍以上：要么计划不贴现实（该改），要么真正在做的事没被记进计划（该补）。",
                    landsIn = "add_action",
                    priority = 60,
                )
            }
        }

        return out
    }

    /**
     * 把缺口写进系统提示。
     *
     * 只写「这一轮该问的那一个」，加上「为什么问它」和「答案落在哪」——
     * 写一堆候选，模型就会挑最容易的、或者干脆挨个问一遍。
     */
    fun prompt(plan: Plan, askOne: Boolean = true): String {
        if (plan.gap == null) {
            return if (plan.cooling.isEmpty()) {
                """
                    # 追问
                    现在没有算得出来的缺口：今天有主线、事项的底子齐、没有未排时间的下一步、没有没写卡点的卡住行动。
                    那就别为了问而问——直接给判断和下一步。需要核实说法时按宪章走（一次只问一个问题）。
                """.trimIndent()
            } else {
                """
                    # 追问
                    还有 ${plan.cooling.size} 个缺口没补（${plan.cooling.joinToString("、") { it.kind.label }}），但都在冷却期里——最近刚问过。
                    这一轮不要重问同样的问题，否则就是唠叨不是追问。用户要是主动提到其中哪一件，就顺着那件问；否则先把手上能给的说清楚。
                """.trimIndent()
            }
        }
        val g = plan.gap
        val ask = if (askOne) "\n这一轮**只问这一个**。问完就停，不要顺手把别的也问了——一次抛两三个问题，他一个都不会答。" else ""
        return buildString {
            append("# 追问（这一轮先问这个）\n")
            append("缺口类型：").append(g.kind.label)
            if (g.subject.isNotBlank()) append("｜对象：").append(g.subject)
            append('\n')
            append("问什么：").append(g.ask).append('\n')
            append("为什么问它：").append(g.why).append('\n')
            if (g.landsIn.isNotBlank()) {
                append("答案落到哪：").append(g.landsIn)
                append("（他答完就直接写进记录，别只是说“好的”；写不了就说清楚缺什么）\n")
            }
            append(ask)
            if (plan.gaps.size > 1) {
                append("\n还有 ")
                append(plan.gaps.size - 1)
                append(" 个缺口排在后面（").append(plan.gaps.drop(1).joinToString("、") { it.kind.label }).append("）——")
                append("这一轮不要问它们。答完这一个，下一轮自然会轮到下一个。\n")
            }
        }.trimEnd()
    }

    /** 用户这句话是不是在回答某个缺口（方便把答案落到对应字段，而不是当闲聊）。 */
    fun looksLikeAnswer(gap: Gap, text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        // 明确在敷衍的，不算回答
        if (t in setOf("嗯", "哦", "好", "随便", "不知道", "再说", "算了")) return false
        // 问了对象名，回答里提到它，基本就是在答这个
        if (gap.subject.isNotBlank() && t.contains(gap.subject)) return true
        // 问的是具体字段，回答长度够就当作在答（真正的判定交给模型）
        return t.length >= 4
    }

    /**
     * 把「用户刚提到的东西」抽出来当追问的焦点。
     *
     * 不做中文分词（没有依赖可用，也不值得为此引一个）：取用户这句话里出现在
     * 事项 / 目标标题中的那几个词，命中谁就把谁提上来。
     */
    fun focusOf(env: AgentEnv, text: String): String {
        val t = text.trim()
        if (t.isBlank()) return ""
        val titles = (env.work().threads().map { it.title } + Aims.open(env.plans()).map { it.title })
            .filter { it.isNotBlank() }
        // 用户提到完整标题，或标题里较长的片段（≥2 字）出现在他的话里
        titles.firstOrNull { t.contains(it) }?.let { return it }
        return titles
            .mapNotNull { title -> title.takeIf { it.length >= 2 && t.contains(it.take(2)) } }
            .firstOrNull()
            .orEmpty()
    }

    /** 行动的 plan 是不是落在今天。 */
    private fun plannedToday(plan: com.zongkong.core.work.Plan, now: Long, zone: ZoneId): Boolean =
        com.zongkong.core.work.TimeParse.startMillis(plan, zone)?.let { DayClock.dateKey(it, zone) == DayClock.dateKey(now, zone) } ?: false

    /** 事项 / 行动在提示词里的一句话叫法。 */
    fun label(r: Rec?): String = r?.title ?: "未记录"
}
