package com.yishou.app.library

import androidx.room.withTransaction
import com.yishou.app.data.*
import com.yishou.app.card.Card
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryStore(private val db: AppDatabase) {
    private val lock = Mutex()
    private var seeded = false
    private val dao get() = db.everyday()
    suspend fun defaults() { if (seeded) return; Classification.defaults.forEach { dao.insertCategory(LibraryCategory(it.id, it.name)) }; seeded = true }
    suspend fun assign(kind: String, id: Long, text: String, selected: Long? = null) {
        defaults()
        val tabs = dao.allCategories().map { Topic(it.id, it.name) }
        val valid = selected?.takeIf { id -> tabs.any { it.id == id } }
        val row = LibraryItem(kind, id, Classification.choose(text, kind, tabs, valid), valid != null)
        if (valid != null) dao.assign(row) else dao.assignAutomatic(row)
    }
    /** 给升级前的内容补分类，只处理还没有归档的条目，手工分类不会被覆盖。 */
    suspend fun indexMissing() = lock.withLock {
        defaults()
        dao.unfiledCards().forEach { row ->
            val c = runCatching { Card.fromJson(row.json) }.getOrNull() ?: return@forEach
            assign("card", row.id, (c.sentences + c.question).joinToString("\n"))
        }
        dao.unfiledMaterials().forEach { assign("material", it.id, it.title + "\n" + it.text) }
    }
    suspend fun add(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "分类名写一两个字也行"
        if (dao.insertCategory(LibraryCategory(name = n)) == -1L) return "这个分类已经有了"
        return null
    }
    suspend fun rename(id: Long, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "分类名不能为空"
        if (dao.allCategories().any { it.id != id && it.name == n }) return "这个分类已经有了"
        dao.renameCategory(id, n); return null
    }
    suspend fun remove(id: Long) {
        if (id <= 5) return
        db.withTransaction { dao.moveCategory(id, 5); dao.deleteCategory(id) }
    }
}
