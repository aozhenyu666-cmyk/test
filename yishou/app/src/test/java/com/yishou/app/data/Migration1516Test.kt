package com.yishou.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class Migration1516Test {
    @Test fun `升级保留旧线程 聊天和分类能读写 手动分类不被自动覆盖`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                if (method.name == "execSQL") { c.createStatement().use { it.execute(args!![0] as String) }; null }
                else error(method.name)
            } as SupportSQLiteDatabase
            AppDatabase.MIGRATION_14_15.migrate(db)
            c.createStatement().use { it.execute("INSERT INTO strand(json,done,updatedAt) VALUES ('旧断点',0,5)") }
            AppDatabase.MIGRATION_15_16.migrate(db)
            c.createStatement().use { s ->
                s.execute("PRAGMA foreign_keys=ON")
                s.execute("INSERT INTO assistant_chat(title,json,updatedAt) VALUES ('一段对话','[]',10)")
                s.execute("INSERT INTO library_category(id,name) VALUES (1,'行测'), (8,'英语')")
                s.execute("INSERT INTO library_item(kind,refId,categoryId,manual) VALUES ('material',10,8,1)")
                s.execute("INSERT OR IGNORE INTO library_item(kind,refId,categoryId,manual) VALUES ('material',10,1,0)")
                s.executeQuery("SELECT json FROM strand WHERE id=1").use { assertTrue(it.next()); assertEquals("旧断点", it.getString(1)) }
                s.executeQuery("SELECT title FROM assistant_chat").use { assertTrue(it.next()); assertEquals("一段对话", it.getString(1)) }
                s.executeQuery("SELECT categoryId,manual FROM library_item").use { assertTrue(it.next()); assertEquals(8, it.getInt(1)); assertEquals(1, it.getInt(2)) }
            }
        }
    }
}
