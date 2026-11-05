package com.yishou.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

/** 真执行迁移 SQL，检查旧记录和三张新表；手机上的 Room 打开另交真机验收。 */
class Migration1415Test {
    @Test fun `十四升十五不丢旧表 新表和索引可以读写`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE old_material (id INTEGER PRIMARY KEY, body TEXT NOT NULL)"); it.execute("INSERT INTO old_material VALUES (1, '旧素材')") }
            val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                if (method.name == "execSQL") { c.createStatement().use { it.execute(args!![0] as String) }; null }
                else error("迁移不应调用 ${method.name}")
            } as SupportSQLiteDatabase
            AppDatabase.MIGRATION_14_15.migrate(db)
            c.createStatement().use { s ->
                s.execute("INSERT INTO strand(json,done,updatedAt) VALUES ('{}',0,42)")
                s.execute("INSERT INTO reflex(signal,action,source,createdAt,dueAt,reps) VALUES ('只有','先检查必要条件','card-1',1,2,0)")
                s.execute("INSERT INTO fade(kind,wins,misses) VALUES ('MAIN',3,0)")
                s.executeQuery("SELECT body FROM old_material WHERE id=1").use { assertTrue(it.next()); assertEquals("旧素材", it.getString(1)) }
                s.executeQuery("SELECT id, updatedAt FROM strand WHERE done=0").use { assertTrue(it.next()); assertEquals(1L, it.getLong(1)); assertEquals(42L, it.getLong(2)) }
                s.executeQuery("SELECT action FROM reflex").use { assertTrue(it.next()); assertEquals("先检查必要条件", it.getString(1)) }
                s.executeQuery("SELECT wins FROM fade WHERE kind='MAIN'").use { assertTrue(it.next()); assertEquals(3, it.getInt(1)) }
                s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name IN ('index_strand_done','index_strand_updatedAt','index_reflex_dueAt')").use { assertTrue(it.next()); assertEquals(3, it.getInt(1)) }
            }
        }
    }
}
