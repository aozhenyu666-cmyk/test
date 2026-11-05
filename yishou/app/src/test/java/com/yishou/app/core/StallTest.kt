package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallTest {

    @Test
    fun `empty answer is silence`() {
        assertEquals(StallKind.SILENT, Stall.classify(null))
        assertEquals(StallKind.SILENT, Stall.classify("   "))
    }

    @Test
    fun `short filler answer is perfunctory`() {
        assertEquals(StallKind.PERFUNCTORY, Stall.classify("嗯"))
        assertEquals(StallKind.PERFUNCTORY, Stall.classify("随便"))
        assertEquals(StallKind.PERFUNCTORY, Stall.classify("不知道"))
    }

    @Test
    fun `four buttons are recognised`() {
        assertEquals(StallKind.CONFUSED, Stall.classify("没看懂"))
        assertEquals(StallKind.STUCK, Stall.classify("卡住了"))
        assertEquals(StallKind.TIRED, Stall.classify("太累"))
        assertEquals(StallKind.REFUSE, Stall.classify("不想做"))
    }

    @Test
    fun `a real explanation is not a stall`() {
        val answer = "因为原文第二句说，必要条件被换成了充分条件，所以 B 不成立。"
        assertEquals(StallKind.ANSWERED, Stall.classify(answer))
    }

    @Test
    fun `tapped button wins over text`() {
        assertEquals(StallKind.TIRED, Stall.classify("好的", tapped = StallKind.TIRED))
    }

    @Test
    fun `silent gets a low bandwidth follow up first`() {
        val reply = Stall.respond(StallContext(StallKind.SILENT, followUps = 0))
        assertTrue(reply is StallReply.FollowUp)
        assertEquals(1, (reply as StallReply.FollowUp).followUps)
        assertTrue(reply.text.contains("回一个字"))
    }

    @Test
    fun `perfunctory is converted to a yes or no question once`() {
        val reply = Stall.respond(StallContext(StallKind.PERFUNCTORY, followUps = 0))
        assertTrue(reply is StallReply.FollowUp)
        assertTrue((reply as StallReply.FollowUp).text.contains("只回字母"))
    }

    @Test
    fun `never asks more than three times`() {
        for (n in 0..3) {
            val reply = Stall.respond(StallContext(StallKind.SILENT, followUps = n))
            if (n >= Stall.MAX_FOLLOW_UPS) {
                assertTrue("第 $n 次应该只要时间", reply is StallReply.AskReturnTime)
            }
        }
        val reply = Stall.respond(StallContext(StallKind.SILENT, followUps = 3))
        assertTrue((reply as StallReply.AskReturnTime).text.contains("时间"))
    }

    @Test
    fun `being away for half an hour skips the chase`() {
        val reply = Stall.respond(
            StallContext(StallKind.SILENT, followUps = 0, silentMinutes = Stall.AWAY_MINUTES),
        )
        assertTrue(reply is StallReply.AskReturnTime)
    }

    @Test
    fun `tired and refuse are allowed to stop immediately`() {
        assertTrue(Stall.respond(StallContext(StallKind.TIRED)) is StallReply.Close)
        assertTrue(Stall.respond(StallContext(StallKind.REFUSE)) is StallReply.Close)
    }

    @Test
    fun `no reply ever blames the user`() {
        val banned = listOf("懒", "借口", "拖延", "必须", "应该", "又", "罚", "欠")
        val texts = buildList {
            for (kind in StallKind.values()) {
                for (n in 0..4) {
                    when (val r = Stall.respond(StallContext(kind, followUps = n, silentMinutes = 0))) {
                        is StallReply.FollowUp -> add(r.text)
                        is StallReply.AskReturnTime -> add(r.text)
                        is StallReply.Close -> add(r.text)
                    }
                }
            }
        }
        for (text in texts) {
            for (word in banned) {
                assertFalse("回复里不该出现「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun `substance ignores punctuation and filler`() {
        assertEquals(0, Stall.substance("嗯，。！"))
        assertEquals(0, Stall.substance("好的"))
        assertEquals(2, Stall.substance("好的谢谢"))
        assertTrue(Stall.substance("因为原文这么写") > 4)
    }
}
