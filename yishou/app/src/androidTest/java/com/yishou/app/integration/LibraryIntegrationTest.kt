package com.yishou.app.integration

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yishou.app.data.*
import com.yishou.app.library.LibraryStore
import com.yishou.app.thread.*
import com.yishou.app.thread.MoveKind as StrandMoveKind
import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.Point
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.reset

@RunWith(AndroidJUnit4::class)
class LibraryIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Before fun before() = runBlocking { reset() }
    @Test fun importFilesAndManualMovesSurviveARealDatabaseReopen() = runBlocking {
        val file = java.io.File(app.filesDir, "english-import.txt").apply { writeText("英语阅读材料：先找这一段的主张，再找支持它的证据，最后指出成立需要的条件。") }
        try {
            Assert.assertNull(app.library.add("英语"))
            val english = app.database.everyday().allCategories().first { it.name == "英语" }.id
            val results = app.fileImport.importAll(listOf(android.net.Uri.fromFile(file)), english) { }
            Assert.assertTrue(results.single().ok)
            val entries = app.database.everyday().materials().first()
            Assert.assertEquals(1, entries.size)
            val material = entries.single()
            Assert.assertEquals(english, app.database.everyday().assignments().first().single().categoryId)
            app.library.assign("material", material.id, material.text, 5)
            app.library.indexMissing()
            Assert.assertEquals(5L, app.database.everyday().assignments().first().single().categoryId)
            val reopened = AppDatabase.create(app)
            try {
                Assert.assertEquals(material.text, reopened.think().getMaterial(material.id)!!.text)
                Assert.assertEquals(5L, reopened.everyday().assignments().first().single().categoryId)
            } finally { reopened.close() }
            Assert.assertNull(app.library.rename(english, "英语阅读"))
            app.library.assign("material", material.id, material.text, english)
            app.library.remove(english)
            Assert.assertFalse(app.database.everyday().allCategories().any { it.id == english })
            Assert.assertEquals(5L, app.database.everyday().assignments().first().single().categoryId)
            Assert.assertNotNull(app.database.think().getMaterial(material.id))
        } finally { file.delete() }
    }
    @Test fun batchQuestionsAndSoupArticlesAreFiledSeparatelyAndNoContentIsLost() = runBlocking {
        val imported = app.pool.take(RuntimeSupport.cardText, MaterialSource.PASTE)
        Assert.assertTrue(imported.ok)
        app.pool.take("海龟汤。汤面：一个人进了餐厅却突然离开了，为什么？汤底：他发现店里所有位置都已经被预订了。", MaterialSource.PASTE)
        val tags = app.database.everyday().assignments().first()
        Assert.assertEquals(1L, tags.single { it.kind == "card" }.categoryId)
        Assert.assertEquals(3L, tags.single { it.kind == "material" }.categoryId)
        Assert.assertEquals(1, app.database.everyday().cards().first().size)
        Assert.assertTrue(app.database.everyday().materials().first().single().text.contains("汤底"))
    }
    @Test fun migrationFromFourteenOpensThroughRoomAndThreadQuestionSurvivesReopen() = runBlocking {
        val name = "runtime-migration.db"
        migration.createDatabase(name, 14).apply {
            execSQL("INSERT INTO material(id,title,text,source,createdAt,usedAt,uses) VALUES (42,'旧素材','不能丢的正文','paste',1,NULL,0)")
            close()
        }
        migration.runMigrationsAndValidate(name, 16, true, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16).close()
        fun open() = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16).build()
        val first = open()
        val id: Long
        val q = LockedQ("固定断点", "周六最晚几点到车站？", points = listOf(Point("P1", "明确的到达时间")), need = 1, reference = "")
        try {
            Assert.assertEquals("不能丢的正文", first.think().getMaterial(42)!!.text)
            val store = StrandStore(first.strands())
            val s = Strand(goal = "确定周六出发时间", doneWhen = "留够半小时并赶上车", items = listOf(Item("I1", "路上用多久"), Item("I2", "最晚几点到")), pending = Pending(StrandMoveKind.STEP, "I1", q), createdAt = 10, updatedAt = 10)
            id = store.save(s).id
            LibraryStore(first).defaults()
        } finally { first.close() }
        val second = open()
        try {
            Assert.assertEquals(q, StrandStore(second.strands()).get(id)!!.pending!!.q)
            Assert.assertEquals(5, second.everyday().allCategories().size)
        } finally { second.close(); app.deleteDatabase(name) }
    }
}
