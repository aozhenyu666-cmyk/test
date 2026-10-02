package com.behaviordept.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.behaviordept.app.data.AppDatabase
import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.Review
import com.behaviordept.app.data.Rule
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.SubSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * v1 → v2 的迁移不能丢数据。做法：先用 Room 建一个 v2 库并写数据，再把 v2 新增的列和表去掉、
 * 版本号改回 1，造出一个 v1 库；然后用正式的迁移重新打开，Room 会逐表校验结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MigrationTest {
    @Test
    fun v1DataSurvivesMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)

        val v2 = AppDatabase.build(context, name)
        val skillId = v2.skillDao().insertSkill(Skill(name = "三角洲行动", template = "competitive"))
        val subId = v2.skillDao().insertSubSkill(SubSkill(skillId = skillId, name = "身法", standard = "不原地对枪"))
        v2.skillDao().insertDrill(Drill(subSkillId = subId, method = "单向 peek 20 次", minutes = 10, source = "manual"))
        v2.guardDao().insertRule(Rule(packages = "com.xingin.xhs", dailyLimitMin = 30, effectiveAt = 1))
        v2.studyDao().insertReview(Review(unitId = 1, intervalDays = 1, questions = "[]", mode = "ai", createdAt = 1))
        v2.close()

        // 造出 v1：去掉 v2 新增的列和表
        val path = context.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE `skill` DROP COLUMN `focusSince`")
            db.execSQL("ALTER TABLE `review` DROP COLUMN `dueAt`")
            db.execSQL("ALTER TABLE `drill` DROP COLUMN `title`")
            db.execSQL("ALTER TABLE `drill` DROP COLUMN `metric`")
            db.execSQL("ALTER TABLE `drill` DROP COLUMN `active`")
            db.execSQL("ALTER TABLE `rule` DROP COLUMN `name`")
            db.execSQL("ALTER TABLE `rule` DROP COLUMN `pendingPackages`")
            db.execSQL("ALTER TABLE `rule` DROP COLUMN `pendingDelete`")
            db.execSQL("DROP TABLE `exam_section`")
            db.execSQL("DROP TABLE `exam_sitting`")
            db.version = 1
        }

        // 用正式迁移打开；结构不对 Room 会直接抛异常
        val migrated = AppDatabase.build(context, name)
        val skills = migrated.skillDao().skills()
        assertEquals(listOf("三角洲行动"), skills.map { it.name })
        assertNull(skills.first().focusSince)
        val drills = migrated.skillDao().activeDrills(subId)
        assertEquals(1, drills.size)
        assertTrue(drills.first().active)
        assertEquals("", drills.first().title)
        val rule = migrated.guardDao().rules().single()
        assertEquals("", rule.name)
        assertEquals(false, rule.pendingDelete)
        assertEquals(1, migrated.studyDao().allReviews().size)
        assertEquals(0, migrated.skillDao().sittingsOf(skillId).size)
        migrated.close()
    }
}
