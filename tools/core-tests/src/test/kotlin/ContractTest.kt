/** 墨灵 ↔ 总控 的约定：两边的 core 编在一起，检查墨灵发出去的总控都认、总控给的墨灵都读得出。 */
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkWire
import com.zongkong.core.moling.Moling
import com.zongkong.core.moling.MolingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContractTest {
    @Test fun everyValidMolingCommandPassesZongkongCheck() {
        val all = listOf(
            ZkCommand.SetMain("写申论", 1_000L, "800 字", "水道"),
            ZkCommand.SetMain("写申论", 0L, "", ""),
            ZkCommand.AddAction("背单词", 1_000L, 2_000L, "", "水道"),
            ZkCommand.StartFocus(25, "写申论", "偏了"),
            ZkCommand.EnterHq(30, "乱了"),
            ZkCommand.Tighten("block_app", "com.ss.android.ugc.aweme", "刷多了"),
            ZkCommand.RequestLoosen("unblock_app", "tv.danmaku.bili", "周末"),
            ZkCommand.RequestLoosen("play_quota", "90", "周末"),
            ZkCommand.Note("开头先亮观点", "对话"),
            ZkCommand.Tighten("strict", "90", "今晚不刷"),
            ZkCommand.Tighten("cut_play", "30", "刷多了"),
            ZkCommand.Commit("写完第二题", 5_000L, "看行动记录", "他说的"),
            ZkCommand.Directive("申论三篇", "这周写完三篇", "要做｜周三第一篇｜周三 21:00", "谋划"),
        )
        all.forEach { c -> assert(ZkWire.valid(c)); assertNull(c.toString(), Moling.check(ZkWire.toMap(c))) }
    }

    @Test fun autoFlagReachesZongkongAndGrantableKindsAgree() {
        val strict = ZkWire.toMapAuto(ZkCommand.Tighten("strict", "90", ""))
        assertEquals("true", strict["auto"])
        assertEquals(com.zongkong.core.moling.GrantKind.STRICT, com.zongkong.core.moling.Grants.kindOf(strict))
        assertNull("永久拦截不能授权", com.zongkong.core.moling.Grants.kindOf(ZkWire.toMapAuto(ZkCommand.Tighten("block_app", "a.b", ""))))
        assertNull("放宽不能授权", com.zongkong.core.moling.Grants.kindOf(ZkWire.toMapAuto(ZkCommand.RequestLoosen("unblock_app", "a.b", ""))))
    }

    @Test fun neverTouchListsAgree() {
        com.moling.core.power.Charter.neverTouch.forEach { pkg ->
            assert(pkg in com.zongkong.core.moling.MolingLink.neverBlock) { pkg }
        }
    }

    @Test fun statusRoundTrip() {
        val z = MolingStatus(at = 7, accessibilityOn = true, heartbeatAt = 6, userDisabled = true, strict = true, strictReason = "专注锁", focus = true,
            hqActive = true, quiet = true, mainTitle = "写申论", mainKey = "k1", mainPlannedAt = 9, mainStarted = true, mainDone = true, lastProgressAt = 8,
            playMinutesToday = 40, workMinutesToday = 90, playLeftMin = 10, spendTodayCents = 1234, credit = 70, stance = "审慎",
            aims = listOf("上岸"), weekDeliverables = listOf("申论 3 篇"), upcoming = listOf("背单词"), blocked = listOf("a.b"), sentinelOn = true)
        val m = ZkWire.status(z.copy(bounces = 3, emergencies = 1, gatesDone = 2, gatesTotal = 4, openCommitments = 5, holdUntil = 99, cutToday = 30, grants = "· 开专注锁").toMap())
        assertEquals(listOf(3, 1, 2, 4, 5, 30), listOf(m.bounces, m.emergencies, m.gatesDone, m.gatesTotal, m.openCommitments, m.cutToday))
        assertEquals(99L, m.holdUntil)
        assertEquals("· 开专注锁", m.grants)
        assertEquals(listOf(7L, 6L, 9L, 8L, 1234L), listOf(m.at, m.heartbeatAt, m.mainPlannedAt, m.lastProgressAt, m.spendTodayCents))
        assertEquals(listOf(true, true, true, true, true, true, true, true, true), listOf(m.accessibilityOn, m.userDisabled, m.strict, m.focus, m.hqActive, m.quiet, m.mainStarted, m.mainDone, m.sentinelOn))
        assertEquals(listOf("专注锁", "写申论", "k1", "审慎"), listOf(m.strictReason, m.mainTitle, m.mainKey, m.stance))
        assertEquals(listOf(40, 90, 10, 70), listOf(m.playMinutesToday, m.workMinutesToday, m.playLeftMin, m.credit))
        assertEquals(listOf(listOf("上岸"), listOf("申论 3 篇"), listOf("背单词"), listOf("a.b")), listOf(m.aims, m.weekDeliverables, m.upcoming, m.blocked))
    }
}
