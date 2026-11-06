package com.yishou.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yishou.app.core.proactive.MemoryType
import com.yishou.app.core.proactive.RelationMemory
import com.yishou.app.core.proactive.Topic
import com.yishou.app.core.proactive.TopicState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 关系记忆的本地存储：扩展在现有 yishou.db 上，不另起一套数据库。
 * 所有查询都走参数绑定。
 */
class RelationStore(private val db: SupportSQLiteDatabase) {

    suspend fun openTopics(limit: Int = 5): List<Topic> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Topic>()
        db.query(
            "SELECT id,title,state,createdAt,lastMentionedAt,followUpAt,summary " +
                "FROM relation_topic WHERE state = ? ORDER BY followUpAt ASC LIMIT ?",
            arrayOf(TopicState.OPEN.name, limit),
        ).use { c ->
            while (c.moveToNext()) {
                out += Topic(
                    id = c.getLong(0),
                    title = c.getString(1),
                    state = runCatching { TopicState.valueOf(c.getString(2)) }.getOrDefault(TopicState.OPEN),
                    createdAt = c.getLong(3),
                    lastMentionedAt = c.getLong(4),
                    followUpAt = c.getLong(5),
                    summary = c.getString(6).orEmpty(),
                )
            }
        }
        out
    }

    suspend fun addTopic(t: Topic): Long = withContext(Dispatchers.IO) {
        db.insert(
            "relation_topic",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                if (t.id > 0) put("id", t.id)
                put("title", t.title)
                put("state", t.state.name)
                put("createdAt", t.createdAt)
                put("lastMentionedAt", t.lastMentionedAt)
                put("followUpAt", t.followUpAt)
                put("summary", t.summary)
            },
        )
    }

    suspend fun updateTopic(t: Topic) = withContext(Dispatchers.IO) {
        db.update(
            "relation_topic",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("title", t.title)
                put("state", t.state.name)
                put("lastMentionedAt", t.lastMentionedAt)
                put("followUpAt", t.followUpAt)
                put("summary", t.summary)
            },
            "id = ?",
            arrayOf(t.id),
        )
    }

    suspend fun addMemory(m: RelationMemory): Long = withContext(Dispatchers.IO) {
        db.insert(
            "relation_memory",
            SQLiteDatabase.CONFLICT_REPLACE,
            ContentValues().apply {
                put("type", m.type.name)
                put("text", m.text)
                put("source", m.source)
                put("observedAt", m.observedAt)
                put("validFrom", m.validFrom)
                if (m.validTo == null) putNull("validTo") else put("validTo", m.validTo)
                put("confidence", m.confidence)
                if (m.correctsId == null) putNull("correctsId") else put("correctsId", m.correctsId)
                if (m.topicId == null) putNull("topicId") else put("topicId", m.topicId)
                put("sensitivity", m.sensitivity)
            },
        )
    }

    suspend fun recentMemories(limit: Int = 30): List<RelationMemory> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RelationMemory>()
        db.query(
            "SELECT id,type,text,source,observedAt,validFrom,validTo,confidence,correctsId,topicId,sensitivity " +
                "FROM relation_memory ORDER BY observedAt DESC LIMIT ?",
            arrayOf(limit),
        ).use { c ->
            while (c.moveToNext()) {
                out += RelationMemory(
                    id = c.getLong(0),
                    type = runCatching { MemoryType.valueOf(c.getString(1)) }.getOrDefault(MemoryType.FACT),
                    text = c.getString(2),
                    source = c.getString(3),
                    observedAt = c.getLong(4),
                    validFrom = c.getLong(5),
                    validTo = if (c.isNull(6)) null else c.getLong(6),
                    confidence = c.getDouble(7),
                    correctsId = if (c.isNull(8)) null else c.getLong(8),
                    topicId = if (c.isNull(9)) null else c.getLong(9),
                    sensitivity = c.getInt(10),
                )
            }
        }
        out
    }

    suspend fun closeMemory(id: Long, now: Long) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE relation_memory SET validTo = ? WHERE id = ? AND validTo IS NULL", arrayOf(now, id))
    }
}

/** 幂等建表：Room 不认识这两张表，但允许它们存在。 */
fun createRelationTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS relation_topic(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT NOT NULL," +
            "state TEXT NOT NULL," +
            "createdAt INTEGER NOT NULL," +
            "lastMentionedAt INTEGER NOT NULL," +
            "followUpAt INTEGER NOT NULL," +
            "summary TEXT NOT NULL)",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS relation_memory(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "type TEXT NOT NULL," +
            "text TEXT NOT NULL," +
            "source TEXT NOT NULL," +
            "observedAt INTEGER NOT NULL," +
            "validFrom INTEGER NOT NULL," +
            "validTo INTEGER," +
            "confidence REAL NOT NULL," +
            "correctsId INTEGER," +
            "topicId INTEGER," +
            "sensitivity INTEGER NOT NULL)",
    )
}
