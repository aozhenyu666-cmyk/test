package com.yishou.app.pool

/**
 * “来一手”：不用你挑练法，从池子里挑下一件最值得做的，并说一句为什么。
 * 规则很简单：上次没做好的先回来 → 能逼你自己生假设的（海龟汤）→ 你自己收进来还没练过的 → 到期的 → 刷题。
 * 同一种练法不连着来（连着两次就换），想换可以点“换一个”。
 */
object Next {

    enum class Mode(val label: String) {
        ENDGAME_REDO("残局"), SOUP("海龟汤"), DRILL("练思维"), ENDGAME("残局"), ECHO("回想"), CARDS("刷题"), LINE("主线"), EMPTY("收点素材"),
    }

    /** 池子现在的样子（由界面层从数据库读出来） */
    data class Snapshot(
        /** 上次没点中、到期回来的残局数 */
        val endgameRedo: Int = 0,
        /** 到期的残局数（含上面的） */
        val endgameDue: Int = 0,
        /** 适合煮汤的一道题（id 和题干开头） */
        val soupCard: Pair<Long, String>? = null,
        /** 你自己收进来、还没练过的一段素材 */
        val freshMaterial: Pair<Long, String>? = null,
        /** 一天以前练过、可以回想的素材 */
        val oldMaterial: Pair<Long, String>? = null,
        /** 题库里还没做的题数 */
        val bankLeft: Int = 0,
        /** 在下的主线和它的下一问 */
        val line: Pair<Long, String>? = null,
        /** 已经练过几次练思维（用来轮换练法） */
        val drillsDone: Int = 0,
        /** 最近做过的练法，最近的在前 */
        val recent: List<Mode> = emptyList(),
    )

    data class Pick(val mode: Mode, val id: Long?, val title: String, val reason: String, val lens: String? = null)

    /** 练思维轮着用的练法：都是要你自己生出东西的 */
    val LENSES = listOf("GIST", "ASSUME", "PREDICT", "LINK", "FRAME", "STANCE")

    fun pick(s: Snapshot, skip: Set<Mode> = emptySet()): Pick {
        val c = mutableListOf<Pair<Int, Pick>>()
        if (s.endgameRedo > 0) c += 90 to Pick(Mode.ENDGAME_REDO, null, "残局", "上次没点中的 ${s.endgameRedo} 局回来了，趁还记得再走一遍。")
        s.soupCard?.let { (id, q) -> c += 80 to Pick(Mode.SOUP, id, q, "这道题是问“为什么”的，正好煮汤：只许问是非题，自己问出答案。") }
        s.freshMaterial?.let { (id, t) ->
            val lens = LENSES[s.drillsDone % LENSES.size]
            c += 75 to Pick(Mode.DRILL, id, t, "你收进来的「$t」还没练过。", lens)
        }
        if (s.endgameDue > s.endgameRedo) c += 60 to Pick(Mode.ENDGAME, null, "残局", "有 ${s.endgameDue - s.endgameRedo} 局到期，点出破绽就行，几十秒一局。")
        s.oldMaterial?.let { (id, t) -> c += 50 to Pick(Mode.ECHO, id, t, "「$t」练过一天了，不看原文说说它讲了什么。", "ECHO") }
        if (s.bankLeft > 0) c += 40 to Pick(Mode.CARDS, null, "刷题", "题库里还有 ${s.bankLeft} 道没做，来一组。")
        s.line?.let { (id, q) -> c += 35 to Pick(Mode.LINE, id, q, "主线停在：$q") }
        val scored = c.filter { it.second.mode !in skip }.map { (score, p) ->
            val penalty = when {
                s.recent.take(1).any { same(it, p.mode) } -> 50
                s.recent.take(3).any { same(it, p.mode) } -> 20
                else -> 0
            }
            (score - penalty) to p
        }
        return scored.maxByOrNull { it.first }?.second
            ?: Pick(Mode.EMPTY, null, "收点素材", "池子空了：分享一篇文章、拍一页书，或者导入一份题库，后面的练法都从这里拿。")
    }

    /** 两种残局算同一种练法 */
    private fun same(a: Mode, b: Mode) = a == b || (a in ENDGAMES && b in ENDGAMES)
    private val ENDGAMES = setOf(Mode.ENDGAME, Mode.ENDGAME_REDO)

    fun encode(recent: List<Mode>) = recent.take(5).joinToString(",") { it.name }
    fun decode(s: String?): List<Mode> = s.orEmpty().split(",").mapNotNull { n -> Mode.entries.firstOrNull { it.name == n } }
}
