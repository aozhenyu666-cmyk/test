package com.yishou.app.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTest {
    private val min = 60_000L

    @Test fun parsesTheTemplateTheWayAnAiWouldFillIt() {
        val p = FocusPlans.parse("""
            好的，计划如下：
            【任务】刷行测言语题
            【目标】30 道
            【总时长】60分钟
            【每组】6 分钟
            【休息】1分钟
            【允许的应用】ChatGPT、Gemini，粉笔
        """.trimIndent())!!
        assertEquals("刷行测言语题", p.task)
        assertEquals("30 道", p.goal)
        assertEquals(60, p.totalMin); assertEquals(6, p.workMin); assertEquals(1, p.restMin)
        assertEquals(listOf("ChatGPT", "Gemini", "粉笔"), p.allowedNames)
        assertNull(FocusPlans.check(p))
    }

    @Test fun badPlansAreExplained() {
        assertNull(FocusPlans.parse("随便聊聊"))
        val p = FocusPlans.parse("【任务】刷题\n【总时长】5分钟\n【每组】6分钟")!!
        assertNotNull(FocusPlans.check(p))
        assertNotNull(FocusPlans.check(p.copy(totalMin = 60, workMin = 90)))
        assertNotNull(FocusPlans.check(p.copy(totalMin = 60, restMin = 30)))
    }

    private fun session() = FocusEngine.start(FocusPlan("刷题", "30 道", 60, 10, 2), 0)!!

    @Test fun roundsAndRestsFollowThePlan() {
        val s = session()
        assertEquals(Phase(PhaseKind.WORK, 1, 0, 10 * min), s.phase(0))
        assertEquals(PhaseKind.REST, s.phase(10 * min).kind)
        assertEquals(12 * min, s.phase(11 * min).endsAt)
        assertEquals(Phase(PhaseKind.WORK, 2, 12 * min, 22 * min), s.phase(12 * min))
        // 最后一组被总时长截住
        assertEquals(60 * min, s.phase(59 * min).endsAt)
        assertEquals(PhaseKind.DONE, s.phase(60 * min).kind)
    }

    @Test fun retuningStartsANewRoundNowAndKeepsTheEnd() {
        // 第 2 组做到一半，发现 6 分钟一组更顺
        val s = FocusEngine.retune(session(), 6, 1, "改成小组", 15 * min)!!
        val p = s.phase(15 * min)
        assertEquals(PhaseKind.WORK, p.kind)
        assertEquals(3, p.round)
        assertEquals(21 * min, p.endsAt)
        assertEquals(PhaseKind.REST, s.phase(21 * min).kind)
        assertEquals(4, s.phase(22 * min).round)
        assertEquals(60 * min, s.endsAt)
        assertNull(FocusEngine.retune(s, 1, 1, "", 30 * min))
    }

    @Test fun canOnlyExtendNeverShorten() {
        val s = session()
        assertEquals(70 * min, FocusEngine.extend(s, 10, 30 * min)!!.endsAt)
        assertNull(FocusEngine.extend(s, -10, 30 * min))
        assertNull(FocusEngine.extend(s, 500, 30 * min))
    }

    @Test fun quittingEarlyCostsAWait() {
        var s = FocusEngine.askQuit(session(), 20 * min)
        assertNull(FocusEngine.quit(s, 20 * min + 60_000))
        s = FocusEngine.cancelQuit(s)
        assertNull(FocusEngine.quit(s, 30 * min))
        s = FocusEngine.askQuit(s, 30 * min)
        val ended = FocusEngine.quit(s, 30 * min + FocusEngine.QUIT_WAIT_MS)!!
        assertTrue(!ended.active(30 * min + FocusEngine.QUIT_WAIT_MS))
    }

    @Test fun marksAreNotJudged() {
        var s = FocusEngine.mark(session(), 1, true, "做了 6 道", 10 * min)
        s = FocusEngine.mark(s, 1, false, "其实没做完", 10 * min + 1)
        s = FocusEngine.mark(s, 2, true, "", 22 * min)
        assertEquals(1, s.doneRounds)
        assertEquals(2, s.marks.size)
    }

    @Test fun onlyWhitelistMessagingAndSystemOpenEvenDuringRest() {
        fun d(pkg: String) = FocusRules.decide(pkg, "com.yishou.app", setOf("com.openai.chatgpt"), FocusRules.DEFAULT_MESSAGING,
            setOf("com.bbk.launcher2"), setOf("com.android.settings"))
        assertEquals(FocusRules.Decision.ALLOW, d("com.openai.chatgpt"))
        assertEquals(FocusRules.Decision.ALLOW, d("com.tencent.mm"))
        assertEquals(FocusRules.Decision.ALLOW, d("com.android.settings"))
        assertEquals(FocusRules.Decision.ALLOW, d("com.bbk.launcher2"))
        assertEquals(FocusRules.Decision.ALLOW, d("com.yishou.app"))
        assertEquals(FocusRules.Decision.BLOCK, d("tv.danmaku.bili"))
        assertEquals(FocusRules.Decision.BLOCK, d("com.ss.android.ugc.aweme"))
    }

    @Test fun appNamesFromTheTemplateResolveToInstalledApps() {
        val installed = mapOf("com.openai.chatgpt" to "ChatGPT", "com.google.android.apps.bard" to "Gemini", "com.fenbi.android.servant" to "粉笔")
        val r = FocusRules.resolve(listOf("GPT", "gemini", "粉笔", "抖音"), installed)
        assertEquals("com.openai.chatgpt", r["GPT"])
        assertEquals("com.google.android.apps.bard", r["gemini"])
        assertEquals("com.fenbi.android.servant", r["粉笔"])
        assertNull(r["抖音"])
    }
}
