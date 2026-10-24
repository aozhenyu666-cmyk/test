package com.zongkong.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val rule = Release.Rule()
    private val analysis = "我今天确实碰到一件急事必须用到被拦截的那个应用处理工作上的材料，不是想刷手机娱乐放松。我已经认真想过有没有别的替代办法，比如借同事的电脑或者用网页版，但都走不通，所以才不得不来申请解除。我很清楚这样做的代价，也接受，处理完手头这件事情我就会马上恢复拦截，绝不拖延继续放纵自己。"
    private val confirm = "我再一次确认当下这件急事确实还在，刚才写下的那段分析到现在依然成立，我头脑清醒，知道解除之后会发生什么，愿意为自己的这个决定负全部责任。"

    @Test
    fun handwritingChecks() {
        assertTrue(Release.checkStage1(analysis, rule) is Release.Check.Ok)
        // 字数不够
        assertTrue(Release.checkStage1("想用一下", rule) is Release.Check.No)
        // 凑字数
        assertTrue(Release.checkStage1("要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用要用", rule) is Release.Check.No)
        // 数字标点不算字
        assertTrue(Release.effectiveChars("123、456。789！") == 0)
    }

    @Test
    fun secondStageCannotCopyFirst() {
        assertTrue(Release.checkStage2(confirm, analysis, rule) is Release.Check.Ok)
        // 照抄第一段：拒绝
        assertTrue(Release.checkStage2(analysis, analysis, rule) is Release.Check.No)
    }

    @Test
    fun coolingThenVerifyThenGrant() {
        val w0 = 1_000_000_000_000L
        val m0 = 50_000_000L
        val req = Release.file(Release.Kind.EXIT, analysis, w0, m0, rule)!!
        // 刚提交：冷静中，还没解除
        assertEquals(Release.Phase.COOLING, Release.phase(req, w0, m0, rule))
        assertFalse(Release.granted(req, w0, m0, rule))
        // 冷静期没过：核验无效
        assertNull(Release.verify(req, confirm, w0 + rule.coolingMs - 1, m0 + rule.coolingMs - 1, rule))
        // 冷静期到了：窗口开，核验通过
        val w1 = w0 + rule.coolingMs + 60_000
        val m1 = m0 + rule.coolingMs + 60_000
        assertEquals(Release.Phase.VERIFY, Release.phase(req, w1, m1, rule))
        val granted = Release.verify(req, confirm, w1, m1, rule)!!
        assertEquals(Release.Phase.GRANTED, Release.phase(granted, w1, m1, rule))
        assertTrue(Release.granted(granted, w1, m1, rule))
        // 解除时间用完：恢复
        val w2 = granted.grantedUntil + 1
        assertEquals(Release.Phase.ENDED, Release.phase(granted, w2, m1 + rule.grantMs, rule))
        assertFalse(Release.granted(granted, w2, m1 + rule.grantMs, rule))
    }

    @Test
    fun movingClockForwardDoesNotShortenCooling() {
        val w0 = 1_000_000_000_000L
        val m0 = 50_000_000L
        val req = Release.file(Release.Kind.RELAX, analysis, w0, m0, rule)!!
        // 把墙上时间往前跳到冷静期之后，但开机计时只走了 1 分钟：还在冷静中
        val wJump = w0 + rule.coolingMs + 10 * 60_000
        val mReal = m0 + 60_000
        assertEquals(Release.Phase.COOLING, Release.phase(req, wJump, mReal, rule))
        assertNull(Release.verify(req, confirm, wJump, mReal, rule))
        assertTrue(Release.coolingLeft(req, wJump, mReal, rule) > 0)
    }

    @Test
    fun rebootMakesItWaitMoreNotLess() {
        val w0 = 1_000_000_000_000L
        val m0 = 50_000_000L
        val req = Release.file(Release.Kind.EXIT, analysis, w0, m0, rule)!!
        // 重启：开机计时清零（比第一关记的还小）。墙上时间也才过了 10 分钟 → 还得等
        val wall = w0 + 10 * 60_000
        val mono = 1_000L
        assertEquals(Release.Phase.COOLING, Release.phase(req, wall, mono, rule))
        // 墙上够了、但开机计时因为重启不够：按更保守的，还在冷静
        val wallEnough = w0 + rule.coolingMs + 60_000
        assertEquals(Release.Phase.COOLING, Release.phase(req, wallEnough, mono, rule))
    }

    @Test
    fun missingWindowExpires() {
        val w0 = 1_000_000_000_000L
        val m0 = 50_000_000L
        val req = Release.file(Release.Kind.UNINSTALL, analysis, w0, m0, rule)!!
        // 冷静期 + 窗口都过了，没核验 → 作废
        val late = w0 + rule.coolingMs + rule.stage2WindowMs + 1
        val mLate = m0 + rule.coolingMs + rule.stage2WindowMs + 1
        assertEquals(Release.Phase.EXPIRED, Release.phase(req, late, mLate, rule))
        assertNull(Release.verify(req, confirm, late, mLate, rule))
        assertTrue(Release.done(req, late, mLate, rule))
    }
}
