package com.zongkong.core.vein

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 脉络：谋划思考部的知识结构。体系 → 层级（最多 3 层）→ 节点 → 关系。
 * 和「脉络」App 用同一套规则、同一种 Markdown 文件格式（可以互相导入导出，用 Obsidian 打开）。
 *
 * 原则：结构由你决定，AI 只负责提议——AI 拆出来的东西先进候选，你确认才写入；
 * 推断类节点必须标「把握」和「验证方式」，防止把听起来合理的故事当成规则。
 */

/** 节点类型，一个字的印章。 */
enum class NodeType(val label: String, val seal: String) {
    CONCEPT("概念", "概"), CLAIM("论断", "论"), RULE("规则", "规"), QUESTION("问题", "问"), CASE("案例", "例");

    companion object {
        fun of(s: String) = entries.firstOrNull { it.label == s.trim() } ?: CLAIM
    }
}

enum class VStatus(val label: String) {
    HYPO("假设"), VERIFIED("已验证"), OVERTURNED("已推翻");

    companion object {
        fun of(s: String) = entries.firstOrNull { it.label == s.trim() } ?: HYPO
    }
}

object Rel {
    const val PART = "属于"
    const val CAUSE = "导致"
    const val SUPPORT = "支撑"
    const val COUNTER = "反例"
    const val RIVAL = "对立"
    const val PRE = "前置"
    const val LIKE = "类似"
    val TYPES = listOf(PART, CAUSE, SUPPORT, COUNTER, RIVAL, PRE, LIKE)
    val HINT = mapOf(
        PART to "A 是 B 的一部分", CAUSE to "A 引起 B", SUPPORT to "A 是 B 的证据", COUNTER to "A 削弱 B",
        RIVAL to "A 与 B 互相竞争", PRE to "做 B 之前要先有 A", LIKE to "A 与 B 结构相像",
    )
}

@Serializable
data class VLink(val type: String, val to: String, val why: String = "")

/** 一次回填：验证的结果。 */
@Serializable
data class VCheck(val date: String, val result: String, val status: String, val confidence: Int? = null)

@Serializable
data class VNode(
    val id: String,
    val title: String,
    val type: String = NodeType.CLAIM.label,
    val system: String,
    /** 层级路径，例如 “加工层/二次加工”；空 = 体系根下。 */
    val parent: String = "",
    val keywords: List<String> = emptyList(),
    val status: String = VStatus.HYPO.label,
    val confidence: Int? = null,
    val verify: String = "",
    val source: String = "",
    val links: List<VLink> = emptyList(),
    val checks: List<VCheck> = emptyList(),
    val created: String = "",
    val updated: String = "",
    /** 一句结论。 */
    val claim: String = "",
    /** 最多 3 行说明。 */
    val note: String = "",
    /** 挂在总控的哪件事上（可空）。 */
    val thread: String = "",
) {
    val nodeType: NodeType get() = NodeType.of(type)
    val vStatus: VStatus get() = VStatus.of(status)
}

@Serializable
data class VSystem(val name: String, val levels: List<String> = emptyList(), val desc: String = "", val created: String = "")

/** 温故进度：第几档、下次哪天、上次哪天、复习了几次。 */
@Serializable
data class Recall(val step: Int = -1, val due: String = "", val last: String = "", val n: Int = 0)

/** 候选卡片：AI 拆出来、等你确认的节点。 */
@Serializable
data class Candidate(
    val tmp: String,
    val title: String,
    val type: String,
    val claim: String,
    val keywords: List<String>,
    val status: String,
    val confidence: Int,
    val verify: String,
    val mergeInto: String = "",
    val system: String,
    val parent: String = "",
    val source: String = "",
)

/** 候选关系：from / to 是候选的 tmp 或已有节点 id。 */
@Serializable
data class CandLink(val from: String, val to: String, val type: String, val why: String, val on: Boolean = true)

@Serializable
data class Vein(
    val systems: List<VSystem> = emptyList(),
    val nodes: List<VNode> = emptyList(),
    val review: Map<String, Recall> = emptyMap(),
    val candidates: List<Candidate> = emptyList(),
    val candLinks: List<CandLink> = emptyList(),
    val seq: Int = 0,
) {
    fun node(id: String): VNode? = nodes.firstOrNull { it.id == id }
    fun byTitle(t: String): VNode? = nodes.firstOrNull { it.title == t.trim() } ?: nodes.firstOrNull { it.title.contains(t.trim()) && t.isNotBlank() }
    fun system(name: String): VSystem? = systems.firstOrNull { it.name == name }
    fun list(system: String? = null): List<VNode> = if (system == null) nodes else nodes.filter { it.system == system }

    /** 指向 [id] 的关系：(来源节点, 关系)。 */
    fun incoming(id: String): List<Pair<VNode, VLink>> = nodes.flatMap { n -> n.links.filter { it.to == id }.map { n to it } }

    /** 一跳关系，带方向：out = 本节点是起点。 */
    fun relations(id: String): List<Relation> {
        val n = node(id) ?: return emptyList()
        val out = n.links.mapNotNull { l -> node(l.to)?.let { Relation(l.type, "out", it, l.why) } }
        val inc = incoming(id).map { (o, l) -> Relation(l.type, "in", o, l.why) }
        return out + inc
    }

    fun pathOf(n: VNode): List<String> = listOf(n.system) + n.parent.split('/').filter { it.isNotBlank() } + n.title
}

data class Relation(val type: String, val dir: String, val other: VNode, val why: String)

object Veins {
    private val STEPS = listOf(1, 3, 7, 15, 30, 60)

    fun today(date: LocalDate) = date.toString()

    // ---------- 体系、层级 ----------

    fun addSystem(v: Vein, name: String, desc: String, date: String): Vein {
        val n = name.trim()
        if (n.isEmpty() || v.system(n) != null) return v
        return v.copy(systems = v.systems + VSystem(n, emptyList(), desc.trim(), date))
    }

    /** 加一个层级（“加工层” 或 “加工层/二次加工”），最多 3 层；父层级不存在就一起加。 */
    fun addLevel(v: Vein, system: String, path: String, date: String): Result<Vein> {
        val parts = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.failure(IllegalArgumentException("层级名不能空"))
        if (parts.size > 3) return Result.failure(IllegalArgumentException("层级最多 3 层"))
        val base = if (v.system(system) == null) addSystem(v, system, "", date) else v
        val sys = base.system(system)!!
        val add = (1..parts.size).map { parts.take(it).joinToString("/") }.filter { it !in sys.levels }
        return Result.success(base.copy(systems = base.systems.map { if (it.name == system) it.copy(levels = it.levels + add) else it }))
    }

    // ---------- 节点 ----------

    fun nextId(v: Vein): Pair<Vein, String> {
        var seq = v.seq
        var id: String
        do {
            seq++
            id = "n-%04d".format(seq)
        } while (v.node(id) != null)
        return v.copy(seq = seq) to id
    }

    /** 新建或更新一个节点（按 id）。体系、层级不存在就补上。 */
    fun put(v: Vein, n: VNode, date: String): Vein {
        var w = if (v.system(n.system) == null) addSystem(v, n.system, "", date) else v
        if (n.parent.isNotBlank()) w = addLevel(w, n.system, n.parent, date).getOrDefault(w)
        val node = n.copy(
            keywords = n.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8),
            links = n.links.filter { it.type in Rel.TYPES && it.to != n.id }.distinctBy { it.type + it.to },
            created = n.created.ifBlank { date }, updated = date,
            confidence = n.confidence?.coerceIn(0, 100),
        )
        return w.copy(nodes = if (w.node(n.id) != null) w.nodes.map { if (it.id == n.id) node else it } else w.nodes + node)
    }

    /** 加关系。对立、类似是对称的，只存一条。 */
    fun link(v: Vein, from: String, to: String, type: String, why: String, date: String): Result<Vein> {
        if (type !in Rel.TYPES) return Result.failure(IllegalArgumentException("关系只能是：${Rel.TYPES.joinToString("、")}"))
        val a = v.node(from) ?: return Result.failure(IllegalArgumentException("没有节点 $from"))
        if (v.node(to) == null) return Result.failure(IllegalArgumentException("没有节点 $to"))
        if (from == to) return Result.failure(IllegalArgumentException("不能连自己"))
        if (a.links.any { it.to == to && it.type == type }) return Result.success(v)
        if ((type == Rel.RIVAL || type == Rel.LIKE) && v.node(to)!!.links.any { it.to == from && it.type == type }) return Result.success(v)
        return Result.success(put(v, a.copy(links = a.links + VLink(type, to, why.trim())), date))
    }

    fun unlink(v: Vein, from: String, to: String, type: String, date: String): Vein {
        val a = v.node(from) ?: return v
        return put(v, a.copy(links = a.links.filterNot { it.to == to && it.type == type }), date)
    }

    /** 回填验证结果：记一条，改状态和把握。 */
    fun backfill(v: Vein, id: String, result: String, status: VStatus, confidence: Int?, date: String): Result<Vein> {
        val n = v.node(id) ?: return Result.failure(IllegalArgumentException("没有节点 $id"))
        if (result.isBlank()) return Result.failure(IllegalArgumentException("写一句验证结果"))
        val c = VCheck(date, result.trim(), status.label, confidence?.coerceIn(0, 100))
        return Result.success(put(v, n.copy(checks = n.checks + c, status = status.label, confidence = confidence ?: n.confidence), date))
    }

    /** 删节点：指向它的关系一起删。 */
    fun remove(v: Vein, id: String): Vein = v.copy(
        nodes = v.nodes.filterNot { it.id == id }.map { n -> n.copy(links = n.links.filterNot { it.to == id }) },
        review = v.review - id,
    )

    /** 合并：[drop] 并进 [keep]，激活词、说明、回填、关系都并过来，指向 drop 的关系改指 keep。 */
    fun merge(v: Vein, keep: String, drop: String, date: String): Result<Vein> {
        val k = v.node(keep) ?: return Result.failure(IllegalArgumentException("没有节点 $keep"))
        val d = v.node(drop) ?: return Result.failure(IllegalArgumentException("没有节点 $drop"))
        if (keep == drop) return Result.failure(IllegalArgumentException("同一个节点"))
        val incomingToDrop = v.incoming(drop).filter { it.first.id != keep }
        val merged = k.copy(
            keywords = (k.keywords + d.keywords).distinct().take(6),
            note = listOf(k.note, if (d.claim != k.claim) d.claim else "", d.note).filter { it.isNotBlank() }.joinToString("\n").lines().take(3).joinToString("\n"),
            checks = (k.checks + d.checks).sortedBy { it.date },
            links = (k.links + d.links).filter { it.to != keep && it.to != drop }.distinctBy { it.type + it.to },
        )
        var w = put(remove(v, drop), merged, date)
        // 原来指向 drop 的关系改指 keep
        incomingToDrop.forEach { (from, l) ->
            val f = w.node(from.id) ?: return@forEach
            if (f.links.none { it.to == keep && it.type == l.type }) w = put(w, f.copy(links = f.links + l.copy(to = keep)), date)
        }
        return Result.success(w)
    }

    fun search(v: Vein, q: String): List<VNode> {
        val t = q.trim()
        if (t.isEmpty()) return emptyList()
        return v.nodes.map { n ->
            val score = (if (n.title.contains(t)) 4 else 0) + (if (n.keywords.any { it.contains(t) || t.contains(it) }) 3 else 0) +
                (if (n.claim.contains(t)) 2 else 0) + (if (n.verify.contains(t) || n.note.contains(t)) 1 else 0)
            n to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    // ---------- 候选 ----------

    /** 接受一张候选：新建节点（或并进已有节点），把它涉及的候选关系一起落地。返回新节点 id。 */
    fun accept(v: Vein, tmp: String, date: String): Pair<Vein, String?> {
        val c = v.candidates.firstOrNull { it.tmp == tmp } ?: return v to null
        var w = v.copy(candidates = v.candidates.filterNot { it.tmp == tmp })
        val id: String
        if (c.mergeInto.isNotBlank() && w.node(c.mergeInto) != null) {
            id = c.mergeInto
            val old = w.node(id)!!
            w = put(w, old.copy(keywords = (old.keywords + c.keywords).distinct().take(6)), date)
        } else {
            val (w2, nid) = nextId(w)
            id = nid
            w = put(w2, VNode(
                id = nid, title = c.title, type = c.type, system = c.system, parent = c.parent, keywords = c.keywords,
                status = c.status, confidence = c.confidence, verify = c.verify, source = c.source, claim = c.claim,
            ), date)
        }
        // 候选关系里的 tmp 换成真 id；两头都落地了才写
        w = w.copy(candLinks = w.candLinks.map { l -> l.copy(from = if (l.from == tmp) id else l.from, to = if (l.to == tmp) id else l.to) })
        val ready = w.candLinks.filter { it.on && w.node(it.from) != null && w.node(it.to) != null }
        ready.forEach { l -> w = link(w, l.from, l.to, l.type, l.why, date).getOrDefault(w) }
        w = w.copy(candLinks = w.candLinks - ready.toSet())
        return w to id
    }

    fun discard(v: Vein, tmp: String): Vein = v.copy(
        candidates = v.candidates.filterNot { it.tmp == tmp },
        candLinks = v.candLinks.filterNot { it.from == tmp || it.to == tmp },
    )

    /** 接受全部：跳过“假设却没写验证方式”的（要你自己补）。返回跳过了几张。 */
    fun acceptAll(v: Vein, date: String): Pair<Vein, Int> {
        var w = v
        var skipped = 0
        v.candidates.forEach { c ->
            if (c.status == VStatus.HYPO.label && c.verify.isBlank()) skipped++ else w = accept(w, c.tmp, date).first
        }
        return w to skipped
    }

    /** 和已有节点可能重复：标题相似或激活词重合多。 */
    fun likelyDuplicate(v: Vein, title: String, keywords: List<String>): VNode? = v.nodes.firstOrNull { n ->
        val sim = titleSim(n.title, title)
        val kw = n.keywords.count { it in keywords }
        sim >= 0.6 || (sim >= 0.45 && kw >= 1) || kw >= 3
    }

    // ---------- 体检 ----------

    data class Issue(val key: String, val level: Int, val label: String, val nodes: List<VNode>)
    data class Health(val score: Int, val issues: List<Issue>, val total: Int)

    fun health(v: Vein, system: String? = null, today: LocalDate): Health {
        val nodes = v.list(system)
        val issues = mutableListOf<Issue>()
        fun rule(key: String, level: Int, label: String, test: (VNode) -> Boolean) {
            val hit = nodes.filter(test)
            if (hit.isNotEmpty()) issues += Issue(key, level, label, hit)
        }
        val hypo = VStatus.HYPO.label
        rule("noVerify", 6, "假设没写验证方式") { it.status == hypo && it.verify.isBlank() }
        rule("overconfident", 6, "假设却把握 ≥ 85%：可能把故事当规则") { it.status == hypo && (it.confidence ?: 0) >= 85 }
        rule("broken", 6, "关系指向的节点不存在") { n -> n.links.any { v.node(it.to) == null } }
        rule("weakVerified", 3, "已验证但把握 < 50%") { it.status == VStatus.VERIFIED.label && it.confidence != null && it.confidence < 50 }
        rule("stale", 3, "假设超过 14 天没回填") { n ->
            n.status == hypo && daysBetween(n.checks.lastOrNull()?.date ?: n.created, today) > 14
        }
        rule("orphan", 1, "孤立节点：没有任何关系") { n -> n.links.isEmpty() && v.incoming(n.id).isEmpty() }
        rule("kwCount", 1, "激活词不是 3–5 个") { it.keywords.size !in 3..5 }
        rule("long", 1, "正文超过“一句结论 + 3 行说明”") { n -> n.note.lines().count { it.isNotBlank() } > 3 || n.claim.length > 80 }
        rule("noWhy", 1, "有关系没写理由") { n -> n.links.any { it.why.isBlank() } }
        val rivals = nodes.flatMap { n ->
            n.links.filter { it.type == Rel.RIVAL }.mapNotNull { l -> v.node(l.to)?.takeIf { o -> n.verify.isBlank() || o.verify.isBlank() }?.let { listOf(n, it) } }.flatten()
        }.distinctBy { it.id }
        if (rivals.isNotEmpty()) issues += Issue("rivalNoTest", 6, "对立的两个解释缺少区分方法（至少一方没写验证方式）", rivals)
        val penalty = issues.sumOf { it.level * it.nodes.size }
        val score = if (nodes.isEmpty()) 100 else (100 - penalty * 10 / nodes.size).coerceIn(0, 100)
        return Health(score, issues.sortedByDescending { it.level }, nodes.size)
    }

    // ---------- 溯源 ----------

    data class Trace(val node: VNode, val why: String, val children: List<Trace>)

    /** 沿“导致”往上（根因）或往下（后果），最多 [depth] 层。 */
    fun trace(v: Vein, id: String, up: Boolean, depth: Int = 4): List<Trace> {
        fun walk(cur: String, d: Int, seen: Set<String>): List<Trace> {
            if (d >= depth) return emptyList()
            val next = if (up) v.incoming(cur).filter { it.second.type == Rel.CAUSE }.map { it.first.id to it.second.why }
            else (v.node(cur)?.links.orEmpty()).filter { it.type == Rel.CAUSE }.map { it.to to it.why }
            return next.filter { (nid, _) -> v.node(nid) != null && nid !in seen }.map { (nid, why) ->
                Trace(v.node(nid)!!, why, walk(nid, d + 1, seen + nid))
            }
        }
        return walk(id, 0, setOf(id))
    }

    /** 根因候选：往上走到头的那些。 */
    fun roots(tree: List<Trace>): List<VNode> {
        val out = mutableListOf<VNode>()
        fun c(t: List<Trace>) { t.forEach { if (it.children.isEmpty()) out += it.node else c(it.children) } }
        c(tree)
        return out.distinctBy { it.id }
    }

    /** 最长的一条上游因果链（标题），从根因到本节点。 */
    fun longestChain(tree: List<Trace>, self: String): List<String> {
        var best = listOf(self)
        fun dfs(t: List<Trace>, path: List<String>) {
            if (t.isEmpty() && path.size > best.size) best = path
            t.forEach { dfs(it.children, listOf(it.node.title) + path) }
        }
        dfs(tree, listOf(self))
        return best
    }

    /** 学习路线：沿“前置”拓扑排序，先学的在前。 */
    fun learningPath(v: Vein, id: String): List<VNode> {
        val order = mutableListOf<String>()
        val state = mutableMapOf<String, Int>()
        fun visit(cur: String) {
            if (state[cur] != null) return
            state[cur] = 1
            v.incoming(cur).filter { it.second.type == Rel.PRE }.forEach { visit(it.first.id) }
            state[cur] = 2
            order += cur
        }
        visit(id)
        return order.mapNotNull { v.node(it) }
    }

    // ---------- 温故 ----------

    /** 今天该复习的：假设优先，再是没复习过的，再按到期日。已推翻的不复习。 */
    fun dueReviews(v: Vein, today: LocalDate, limit: Int = 10): List<VNode> {
        val t = today.toString()
        return v.nodes.filter { it.status != VStatus.OVERTURNED.label }.filter { n -> v.review[n.id]?.let { it.due <= t } ?: true }
            .sortedWith(compareBy<VNode>({ if (it.status == VStatus.HYPO.label) 0 else 1 }, { if (v.review[it.id] == null) 0 else 1 }, { v.review[it.id]?.due ?: "" }))
            .take(limit)
    }

    enum class Grade { FORGOT, FUZZY, GOOD }

    /** 记一次复习；返回几天后再见。 */
    fun grade(v: Vein, id: String, g: Grade, today: LocalDate): Pair<Vein, Int> {
        val r = v.review[id] ?: Recall()
        val step = when (g) {
            Grade.GOOD -> (r.step + 1).coerceAtMost(STEPS.lastIndex)
            Grade.FUZZY -> r.step.coerceAtLeast(0)
            Grade.FORGOT -> 0
        }
        val days = if (g == Grade.FORGOT) 1 else STEPS[step]
        val next = Recall(step, today.plusDays(days.toLong()).toString(), today.toString(), r.n + 1)
        return v.copy(review = v.review + (id to next)) to days
    }

    // ---------- 相似 ----------

    /** 标题相似度：字二元组的 Dice 系数。 */
    fun titleSim(a: String, b: String): Double {
        fun grams(s: String) = s.replace(Regex("\\s"), "").windowed(2, 1, partialWindows = false).toSet().ifEmpty { setOf(s) }
        val x = grams(a)
        val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }

    fun daysBetween(date: String, today: LocalDate): Long = runCatching {
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)), today)
    }.getOrDefault(999)

    // ---------- 示例体系 ----------

    /** 和「脉络」App 一样的首个试用体系「思维问题」。 */
    fun seed(v: Vein, date: String): Vein {
        if (v.system("思维问题") != null) return v
        var w = addSystem(v, "思维问题", "把“想不透、想不下去”拆成能检查的故障点和竞争根因。", date)
        w = addLevel(w, "思维问题", "加工层", date).getOrThrow()
        w = addLevel(w, "思维问题", "检验层", date).getOrThrow()
        w = addLevel(w, "思维问题", "输出层", date).getOrThrow()
        w = addLevel(w, "思维问题", "根因", date).getOrThrow()
        data class S(val title: String, val parent: String, val claim: String, val kw: List<String>, val type: String = "论断", val conf: Int = 60, val verify: String = "")
        val seeds = listOf(
            S("第一反应停止", "加工层", "第一反应出现后不再继续加工，把念头当答案。", listOf("二次加工", "停止信号", "依据")),
            S("推理链断", "加工层", "从 A 推到 B 之后，推不出 C、D。", listOf("推理链", "下一问", "中间结论")),
            S("依据缺失", "检验层", "结论没有依据，或者依据只是感觉。", listOf("证据", "依据", "可检验")),
            S("只看一面", "检验层", "没有找反例和竞争解释。", listOf("反例", "竞争解释", "对立")),
            S("结论不落地", "输出层", "想完了没有变成下一步动作。", listOf("下一步", "行动", "落地")),
            S("下一问没有产生", "根因", "根因候选一：脑子里没有“下一个问题”，所以停。", listOf("提问", "好奇", "下一问"), conf = 55, verify = "记一周：每次停下时，能不能说出下一个问题"),
            S("单步成本高、停止信号来得早", "根因", "根因候选二：每想一步都很累，一有“差不多了”的感觉就停。", listOf("认知负荷", "停止信号", "疲劳"), conf = 45, verify = "记一周：停下时的疲劳程度（1–5）"),
        )
        val ids = mutableMapOf<String, String>()
        seeds.forEach { s ->
            val (w2, id) = nextId(w)
            ids[s.title] = id
            w = put(w2, VNode(id = id, title = s.title, type = s.type, system = "思维问题", parent = s.parent, keywords = s.kw, confidence = s.conf, verify = s.verify, source = "示例", claim = s.claim), date)
        }
        fun l(a: String, b: String, t: String, why: String) { w = link(w, ids[a]!!, ids[b]!!, t, why, date).getOrDefault(w) }
        l("第一反应停止", "推理链断", Rel.CAUSE, "不加工就推不下去")
        l("推理链断", "结论不落地", Rel.CAUSE, "推不到底，就没有可执行的结论")
        l("下一问没有产生", "第一反应停止", Rel.CAUSE, "没有下一个问题，第一反应就是终点")
        l("单步成本高、停止信号来得早", "第一反应停止", Rel.CAUSE, "太累就停")
        l("下一问没有产生", "单步成本高、停止信号来得早", Rel.RIVAL, "两个竞争的根因解释")
        l("依据缺失", "只看一面", Rel.LIKE, "都是检验环节缺失")
        return w
    }
}
