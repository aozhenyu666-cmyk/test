package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话段数的升级迁移：默认 1 → 3，但只在“用户没自己设过”的情况下动手。
 *
 * 为什么要标记：老配置里存着的 1 分不清是旧默认还是用户特意设的 1，而 `Defaults.migrate` 每次读配置都会跑，
 * 靠 `threadsMigrated` 把这次改写限制成一次性。全是纯逻辑，不碰 Android。
 */
class ChatConfigMigrateTest {

    private fun chat(perDay: Int, migrated: Boolean = false) = ChatConfig(threadsPerDay = perDay, threadsMigrated = migrated)

    @Test
    fun newDefaultIsThreePerDay() {
        // 用户拍板“总共应该能开两到三个对话”：新装的默认就是 3
        assertEquals(3, ChatConfig().threadsPerDay)
        assertFalse(ChatConfig().threadsMigrated)
        assertEquals(3, Config().chat.threadsPerDay)
    }

    @Test
    fun oldDefaultOneBecomesThreeAndGetsMarked() {
        val migrated = Defaults.migrate(Config(chat = chat(1)))
        assertEquals(3, migrated.chat.threadsPerDay)
        assertTrue(migrated.chat.threadsMigrated)
    }

    @Test
    fun userChosenValuesAreLeftAlone() {
        // 用户自己设过 2 / 5：一个字都不动（他的选择比新默认更可信）
        listOf(2, 5, 0).forEach { v ->
            val migrated = Defaults.migrate(Config(chat = chat(v)))
            assertEquals(v, migrated.chat.threadsPerDay)
        }
        // 没东西要改时返回原对象：Store 每次读配置都调 migrate，不该白白写一次盘
        val mine = Config(chat = chat(2))
        assertSame(mine, Defaults.migrate(mine))
    }

    @Test
    fun migrationIsIdempotentOnceMarked() {
        val once = Defaults.migrate(Config(chat = chat(1)))
        assertEquals(3, once.chat.threadsPerDay)
        // 再跑一次（以及用户之后自己选回 1 段的情况）都不该被再改一遍
        assertSame(once, Defaults.migrate(once))
        val back = Defaults.migrate(Config(chat = chat(1, migrated = true)))
        assertEquals(1, back.chat.threadsPerDay)
        assertTrue(back.chat.threadsMigrated)
    }

    @Test
    fun settingTheNumberInTheAppMarksItMigrated() {
        // 设置页写段数时会一并置标记，所以用户主动选的 1 段不会被升级逻辑改成 3
        val chosen = Config(chat = chat(1, migrated = true))
        assertEquals(1, Defaults.migrate(chosen).chat.threadsPerDay)
    }

    @Test
    fun oldGateMigrationStillWorksAlongside() {
        // 加字段不能把原来的迁移搞坏：老配置里那道 AI 逐条审的日终验收还是要换成新的
        val old = Config(
            gates = Defaults.gates().map {
                if (it.id == "hq_review") it.copy(verify = VerifyMode.AI, rubric = "逐条对照了今天排的行动") else it
            },
            chat = chat(1),
        )
        val migrated = Defaults.migrate(old)
        assertEquals(VerifyMode.TEXT, migrated.gates.first { it.id == "hq_review" }.verify)
        assertEquals(3, migrated.chat.threadsPerDay)
    }

    @Test
    fun oldConfigWithoutTheFieldDecodesToNotMigrated() {
        // 老 config.json 里没有 threadsMigrated：读进来必须是 false，迁移才有机会跑
        val json = """{"chat":{"threadsPerDay":1}}"""
        val c = ZkJson.decodeFromString(Config.serializer(), json)
        assertEquals(1, c.chat.threadsPerDay)
        assertFalse(c.chat.threadsMigrated)
        assertEquals(3, Defaults.migrate(c).chat.threadsPerDay)
    }
}
