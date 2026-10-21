package com.moling.app.mirror

import android.util.Log
import com.moling.app.data.SettingsStore
import com.moling.core.mind.BEIJING
import com.moling.core.mirror.Absorbed
import com.moling.core.mirror.InboxItem
import com.moling.core.mirror.Mirror
import com.moling.core.mirror.Section
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 和 Notion 同步（贯通，v6）：
 *  - 推：把大脑的黑板整块重写进《墨灵黑板》（内容没变就不写，最多半小时一次；点“现在同步”立刻写）；
 *  - 收：《给墨灵的收件箱》里新写的块，和根页面下他自己的其他笔记页（改过的才收），交给大脑把关。
 * 墨灵从不往收件箱里写，也不删他的东西；只重写自己的黑板。
 */
object NotionMirror {
    private const val TAG = "NotionMirror"
    private const val PULL_MS = 15 * 60_000L
    private const val PUSH_MS = 30 * 60_000L

    /** 收件箱第一段的说明以它开头，收的时候跳过。 */
    private const val OWN = "【墨灵的说明】"

    private val busy = AtomicBoolean(false)

    @Volatile private var lastPull = 0L
    @Volatile private var lastPush = 0L
    @Volatile private var pushedHash = ""

    data class Result(val status: String, val absorbed: List<Absorbed>)

    /**
     * 同步一次。没设置、还没到时间（[force] 除外）、正在同步时返回 null。
     * [board] 现取黑板；[seen] 是大脑记着的“收过了”；[absorb] 交给大脑。
     */
    suspend fun sync(
        store: SettingsStore,
        now: Long,
        force: Boolean,
        board: () -> List<Section>,
        seen: () -> Collection<String>,
        absorb: suspend (List<InboxItem>) -> Absorbed,
    ): Result? {
        val n = store.notion()
        if (!n.ready()) return null
        if (!force && now - lastPull < PULL_MS) return null
        if (!busy.compareAndSet(false, true)) return null
        lastPull = now
        try {
            val root = Mirror.pageId(n.root) ?: return done(store, now, "页面链接不对：要贴 Notion 页面的链接", emptyList())
            val api = NotionClient(n.token)
            val (boardId, inboxId) = pages(api, store, root)

            val sections = board()
            val h = Mirror.hash(sections)
            var pushed = false
            if (h != pushedHash && (force || now - lastPush >= PUSH_MS)) {
                api.children(boardId).forEach { api.delete(it.id) }
                api.append(boardId, blocks(sections))
                pushedHash = h
                lastPush = now
                pushed = true
            }

            val known = seen().toSet()
            val items = inbox(api, inboxId, known) + notes(api, root, setOf(boardId, inboxId), known)
            val results = mutableListOf<Absorbed>()
            for (part in items.chunked(6).take(3)) results += absorb(part)

            val got = items.size
            val answered = results.flatMap { it.answered }
            val added = results.sumOf { it.added }
            val text = buildString {
                append(if (pushed) "黑板更新了" else "黑板没变")
                append(if (got == 0) "，收件箱没有新的" else "，收了 $got 条")
                if (added > 0) append("，体系图 +$added")
                if (answered.isNotEmpty()) append("，GPT 回答了 ${answered.joinToString("、")}")
                if (results.any { it.aboutProposal }) append("，有一条“了解”的修改等你点好")
                if (got > 0 && results.all { it.added == 0 && it.answered.isEmpty() && !it.aboutProposal && it.notes.isEmpty() }) append("（模型没接上或没什么可收的，没收的下次再看）")
            }
            return done(store, now, text, results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotionError) {
            // 黑板或收件箱被删了：下次重新找 / 重新建
            if (e.code == 404) store.saveNotionPages("", "")
            pushedHash = ""
            return done(store, now, e.message ?: "Notion 出错了", emptyList())
        } catch (e: IOException) {
            return done(store, now, "连不上 Notion：${e.message ?: e.javaClass.simpleName}", emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "同步失败", e)
            return done(store, now, "同步失败：${e.message ?: e.javaClass.simpleName}", emptyList())
        } finally {
            busy.set(false)
        }
    }

    private fun done(store: SettingsStore, now: Long, text: String, results: List<Absorbed>): Result {
        val t = Instant.ofEpochMilli(now).atZone(BEIJING)
        val status = "%02d:%02d %s".format(t.hour, t.minute, text)
        store.setNotionStatus(status)
        return Result(status, results)
    }

    /** 找到（或建好）黑板和收件箱两个子页面。 */
    private suspend fun pages(api: NotionClient, store: SettingsStore, root: String): Pair<String, String> {
        val n = store.notion()
        if (n.boardId.isNotBlank() && n.inboxId.isNotBlank()) return n.boardId to n.inboxId
        val kids = api.children(root).filter { it.type == "child_page" }
        val board = kids.firstOrNull { it.title == Mirror.BOARD_TITLE }?.id
            ?: api.createPage(root, Mirror.BOARD_TITLE)
        val inbox = kids.firstOrNull { it.title == Mirror.INBOX_TITLE }?.id
            ?: api.createPage(
                root, Mirror.INBOX_TITLE,
                listOf(NotionClient.paragraph("$OWN 在这里写给墨灵的话；GPT / Notion AI 的回答也写在这里（每个回答开头写问题编号，比如 Q3：）。墨灵大约每 15 分钟来收一次，收过的不删。")),
            )
        store.saveNotionPages(board, inbox)
        pushedHash = ""
        return board to inbox
    }

    private fun blocks(sections: List<Section>) = sections.flatMap { s ->
        listOf(NotionClient.heading(s.title)) + s.lines.map { if (s.title.startsWith("给 ")) NotionClient.paragraph(it) else NotionClient.bullet(it) }
    }

    /** 收件箱里新写的：连着的几块合成一条（最多约 2000 字），子页面单独一条。 */
    private suspend fun inbox(api: NotionClient, inboxId: String, known: Set<String>): List<InboxItem> {
        val out = mutableListOf<InboxItem>()
        val group = mutableListOf<NotionBlock>()
        fun flush() {
            if (group.isEmpty()) return
            out += InboxItem(group.first().id, group.joinToString("\n") { it.text }, "收件箱", group.drop(1).map { it.id })
            group.clear()
        }
        for (b in api.children(inboxId)) {
            when {
                b.type == "child_page" -> {
                    flush()
                    val key = "page:${b.id}@${b.edited}"
                    if (key !in known) {
                        val body = api.children(b.id, max = 100).joinToString("\n") { it.text }.trim()
                        if (body.isNotBlank()) out += InboxItem(key, "《${b.title}》\n$body", "收件箱")
                    }
                }
                b.id in known || b.text.isBlank() || b.text.startsWith(OWN) -> flush()
                b.type == "divider" -> flush()
                else -> {
                    if (b.type.startsWith("heading")) flush()
                    group += b
                    if (group.sumOf { it.text.length } >= 2000) flush()
                }
            }
        }
        flush()
        return out
    }

    /** 根页面下他自己的笔记页（不算黑板和收件箱）：改过的才收，每次最多 3 页。 */
    private suspend fun notes(api: NotionClient, root: String, mine: Set<String>, known: Set<String>): List<InboxItem> {
        val out = mutableListOf<InboxItem>()
        for (p in api.children(root).filter { it.type == "child_page" && it.id !in mine }) {
            if (out.size >= 3) break
            val key = "page:${p.id}@${api.edited(p.id)}"
            if (key in known) continue
            val body = api.children(p.id, max = 100).joinToString("\n") { it.text }.trim()
            if (body.isNotBlank()) out += InboxItem(key, "《${p.title}》\n$body", "Notion 笔记")
        }
        return out
    }
}
