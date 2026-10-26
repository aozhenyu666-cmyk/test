package com.yishou.app.pool

import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import com.yishou.app.data.CardSource
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.yishou.app.soup.Soups

/**
 * 素材池：所有东西进来的唯一入口，也是所有练法拿东西的地方。
 * 进来时自动分流（题进题库，文章切段进素材）；进来以后后台顺手加工成残局。
 */
class Pool(private val app: YishouApp) {

    data class Taken(val message: String, val materialId: Long? = null, val bank: Boolean = false, val ok: Boolean = true)

    /** 收进一段文字（粘贴、分享、文件读出来的字都走这里） */
    suspend fun take(text: String, source: String): Taken {
        val taken = when (val r = Intake.route(text)) {
            is Intake.Route.TooShort -> Taken("太短了，至少要一两句完整的话。", ok = false)
            is Intake.Route.Bank -> {
                val b = app.bankImport.import(r.text)
                Taken(b.line(), bank = true, ok = b.total > 0)
            }
            is Intake.Route.Article -> {
                val now = System.currentTimeMillis()
                var first: Long? = null
                r.pieces.forEachIndexed { i, p ->
                    val id = app.database.think().insertMaterial(Material(title = p.title.take(24), text = p.text, source = source, createdAt = now + i))
                    if (first == null) first = id
                }
                Taken(if (r.pieces.size == 1) "收好了：${r.pieces[0].title}" else "收好了：切成 ${r.pieces.size} 段，「${r.pieces[0].title}」起", first)
            }
        }
        if (taken.ok) app.prefetchEndgames()
        return taken
    }

    /** 池子现在的样子，给“来一手”挑下一件用 */
    suspend fun snapshot(): Next.Snapshot {
        val now = System.currentTimeMillis()
        val db = app.database
        val due = db.endgames().due(now, 300)
        val played = db.soups().playedCardIds().toHashSet()
        val soup = db.cards().recent(600).asSequence()
            .filter { it.source == CardSource.BANK && it.id !in played }
            .mapNotNull { row -> runCatching { row.id to Card.parse(org.json.JSONObject(row.json)) }.getOrNull() }
            .firstOrNull { Soups.score(it.second) == 2 }
            ?.let { (id, c) -> id to c.question.take(24) }
        val mats = db.think().recentMaterials(300)
        val own = setOf(MaterialSource.MADE, MaterialSource.BUILTIN, MaterialSource.BANK)
        val fresh = mats.firstOrNull { it.uses == 0 && it.source !in own } ?: mats.firstOrNull { it.uses == 0 && it.source == MaterialSource.BANK }
        val old = mats.filter { it.uses > 0 && (it.usedAt ?: now) < now - 20 * 3_600_000L }.minByOrNull { it.usedAt ?: 0 }
        val line = db.lines().latestOpen()
        // 刚答完的先别回来，隔半小时再想才有用
        val miss = db.scrollQuizzes().toRedo(10).firstOrNull { (it.answeredAt ?: now) < now - 30 * 60_000L }
        return Next.Snapshot(
            endgameRedo = due.count { it.seen > 0 && !it.lastHit },
            endgameDue = due.size,
            scrollMiss = miss?.let { it.id to it.seen.ifBlank { it.question }.take(24) },
            soupCard = soup,
            freshMaterial = fresh?.let { it.id to it.title },
            oldMaterial = old?.let { it.id to it.title },
            bankLeft = db.cards().unusedBank().size,
            line = line?.let { it.id to it.nextQuestion.take(30) },
            drillsDone = db.think().drillsSince(now - 30L * 86_400_000).size,
            recent = recent(),
        )
    }

    private val prefs get() = app.getSharedPreferences("pool", android.content.Context.MODE_PRIVATE)

    fun recent(): List<Next.Mode> = Next.decode(prefs.getString("recent", null))

    /** 记下这次做了哪种练法（下次不连着来） */
    fun did(mode: Next.Mode) {
        prefs.edit().putString("recent", Next.encode(listOf(mode) + recent())).apply()
    }

    /** “来一手”选中以后去哪一页 */
    fun route(p: Next.Pick): String = when (p.mode) {
        Next.Mode.ENDGAME, Next.Mode.ENDGAME_REDO -> "endgame"
        Next.Mode.SCROLL_REDO -> "redo?id=${p.id ?: -1}"
        Next.Mode.SOUP -> "soup?card=${p.id ?: -1}"
        Next.Mode.DRILL -> "think?m=${p.id ?: -1}&lens=${p.lens.orEmpty()}"
        Next.Mode.ECHO -> "think?m=-1&lens=ECHO"
        Next.Mode.CARDS -> "cards"
        Next.Mode.LINE -> "line?id=${p.id ?: -1}"
        Next.Mode.EMPTY -> "pool"
    }
}
