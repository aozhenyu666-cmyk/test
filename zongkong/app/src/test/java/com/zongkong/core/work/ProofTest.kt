package com.zongkong.core.work

import com.zongkong.core.ApiResult
import com.zongkong.core.agent.ChatApi
import com.zongkong.core.agent.ChatMsg
import com.zongkong.core.agent.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ProofTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_800_000_000_000L

    @Test
    fun photoFlags() {
        val start = now - 30 * 60_000
        val fresh = ProofItem(ProofItem.PHOTO, "/a.jpg", takenAt = now - 5 * 60_000, hash = "h1")
        assertTrue(Proof.flags(fresh, start, now, emptyMap(), zone).isEmpty())
        val reused = Proof.flags(fresh, start, now, mapOf("h1" to "背单词"), zone)
        assertTrue(reused.single().contains("背单词"))
        assertTrue(Proof.duplicate(fresh, mapOf("h1" to "背单词")))
        assertTrue(Proof.flags(fresh.copy(takenAt = 0), start, now, emptyMap(), zone).single().contains("截图"))
        assertTrue(Proof.flags(fresh.copy(takenAt = start - 3_600_000), start, now, emptyMap(), zone).single().contains("之前"))
        assertTrue(Proof.flags(ProofItem(ProofItem.LINK, "https://x"), start, now, emptyMap(), zone).isEmpty())
    }

    @Test
    fun parseAndRounds() {
        val r1 = Proof.parse("""好的 {"met":"部分达到","reason":"只看到 10 题","ask":"拍一下后 10 题"}""", 1, now)!!
        assertEquals(WorkOps.Met.PARTIAL.name, r1.met)
        assertEquals("拍一下后 10 题", r1.ask)
        assertTrue(Proof.canRetry(listOf(r1)))
        // 第二轮不许再要
        val r2 = Proof.parse("""{"met":"没达到","reason":"还是只有 10 题","ask":"再拍"}""", 2, now)!!
        assertEquals(WorkOps.Met.NO.name, r2.met)
        assertEquals("", r2.ask)
        assertFalse(Proof.canRetry(listOf(r1, r2)))
        // 达到就不追问
        assertFalse(Proof.canRetry(listOf(Proof.parse("""{"met":"达到","reason":"20 题都对了答案","ask":"x"}""", 1, now)!!)))
        assertEquals(null, Proof.parse("看不懂", 1, now))
    }

    @Test
    fun resultTextSaysWhoJudged() {
        val items = listOf(
            ProofItem(ProofItem.PHOTO, "/a.jpg", takenAt = now - 60_000, hash = "h"),
            ProofItem(ProofItem.LINK, "https://notion.so/x"),
            ProofItem(ProofItem.TEXT, "刷了 20 题"),
        )
        val ai = ProofRound(now, "PARTIAL", "只看到一半")
        val t = Proof.resultText("", items, listOf(ai), WorkOps.Met.YES, "override", zone)
        assertTrue(t, t.startsWith("刷了 20 题"))
        assertTrue(t, t.contains("照片 1 张"))
        assertTrue(t, t.contains("https://notion.so/x"))
        assertTrue(t, t.contains("本人改判为达到"))
        assertTrue(Proof.resultText("写完了", emptyList(), emptyList(), WorkOps.Met.YES, "self", zone).endsWith("自评：达到"))
    }

    @Test
    fun reviewerSendsCollageAndCriteria() = runBlocking {
        var seen: ChatMsg? = null
        val api = ChatApi { system, msgs, _ ->
            assertTrue(system.contains("验收官"))
            seen = msgs.single()
            ApiResult.Ok(Turn("""{"met":"达到","reason":"照片里 20 题都批改了"}""", emptyList()))
        }
        val items = listOf(ProofItem(ProofItem.PHOTO, "/a.jpg", takenAt = now - 60_000, hash = "h"))
        val r = ProofReviewer(api).review("刷 20 道资料分析", "做完并对答案", items, mapOf(0 to listOf("没有拍摄时间")), null, "/collage.jpg", now, zone)
        assertEquals(WorkOps.Met.YES.name, (r as ApiResult.Ok).value.met)
        assertEquals("/collage.jpg", seen!!.image)
        assertTrue(seen!!.content.contains("做完并对答案"))
        assertTrue(seen!!.content.contains("疑点：没有拍摄时间"))
    }
}
