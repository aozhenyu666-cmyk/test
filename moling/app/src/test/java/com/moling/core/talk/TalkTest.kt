package com.moling.core.talk

import com.moling.core.link.ZkCommand
import com.moling.core.mind.BEIJING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TalkTest {
    private val dayStart = LocalDate.of(2026, 10, 21).atStartOfDay(BEIJING).toInstant().toEpochMilli()

    @Test fun newActions() {
        val r = Talk.parse("今晚别刷了。\n【动作】严管｜120\n【动作】约定｜写完申论第二题｜22:00｜看行动记录", dayStart)
        assertEquals("今晚别刷了。", r.text)
        assertEquals(ZkCommand.Tighten("strict", "120", "对话里说好的"), (r.actions[0] as TalkAction.Zk).command)
        val c = (r.actions[1] as TalkAction.Zk).command as ZkCommand.Commit
        assertEquals("写完申论第二题", c.what)
        assertEquals(dayStart + 22 * 3_600_000L, c.due)
        assertEquals("看行动记录", c.check)
    }

    @Test fun cutPlayAndPastMidnightCommit() {
        val r = Talk.parse("【动作】减娱乐｜30\n【动作】约定｜睡觉｜01:00｜看亮屏", dayStart)
        assertEquals(ZkCommand.Tighten("cut_play", "30", "对话里说好的"), (r.actions[0] as TalkAction.Zk).command)
        assertEquals(dayStart + 25 * 3_600_000L, ((r.actions[1] as TalkAction.Zk).command as ZkCommand.Commit).due)
    }

    @Test fun outOfRangeIsDropped() {
        assertTrue(Talk.parse("【动作】严管｜1\n【动作】减娱乐｜999", dayStart).actions.isEmpty())
    }
}
