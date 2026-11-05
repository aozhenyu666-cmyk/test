package com.yishou.app.thread

import com.yishou.app.data.StrandDao
import com.yishou.app.data.StrandRow
import org.json.JSONObject

/** 断点先存下来，再给人看；新建时带回数据库给的编号。 */
class StrandStore(private val dao: StrandDao) {
    suspend fun open(): Strand? = dao.open()?.let(::decode)
    suspend fun get(id: Long): Strand? = dao.get(id)?.let(::decode)
    suspend fun save(s: Strand): Strand {
        // 页面复盘存在同一条记录里，普通保存不把它抹掉。
        val json = s.toJson()
        if (s.id > 0) dao.get(s.id)?.let { old ->
            JSONObject(old.json).optJSONObject("review")?.let { json.put("review", it) }
        }
        val row = StrandRow(s.id, json.toString(), s.done, s.updatedAt)
        return if (s.id == 0L) s.copy(id = dao.insert(row)) else s.also { dao.update(row) }
    }
    private fun decode(row: StrandRow) = Strand.fromJson(row.id, JSONObject(row.json))
}
