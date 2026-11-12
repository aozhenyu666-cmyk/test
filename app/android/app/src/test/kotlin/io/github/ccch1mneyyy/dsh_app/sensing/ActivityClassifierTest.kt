package io.github.ccch1mneyyy.dsh_app.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J3 活动识别。只测纯函数 [ActivityClassifier.classify]：
 * 真读传感器要手机、要权限、要真人走路，本机跑不了。能判错的逻辑就在这个函数里。
 */
class ActivityClassifierTest {

    private fun c(steps: Int, windowMs: Long, cyclingHint: Boolean = false, walkingHint: Boolean = false) =
        ActivityClassifier.classify(steps, windowMs, cyclingHint, walkingHint)

    @Test
    fun windowTooShortIsNotJudged() {
        // 1 秒里走了 2 步，换算成 120 步/分，看着像在走，其实只是抖了一下。
        assertEquals(ActivityKind.UNKNOWN, c(2, 1_000))
        assertEquals(ActivityKind.UNKNOWN, c(0, ActivityClassifier.MIN_WINDOW_MS - 1))
    }

    @Test
    fun justEnoughWindowIsJudged() {
        // 正好 10 秒：59 步 = 354 步/分，能判。
        assertEquals(ActivityKind.WALKING, c(59, ActivityClassifier.MIN_WINDOW_MS))
    }

    @Test
    fun negativeStepsMeansBrokenInput() {
        assertEquals(ActivityKind.UNKNOWN, c(-1, 60_000))
    }

    @Test
    fun noStepsInAMinuteIsStill() {
        assertEquals(ActivityKind.STILL, c(0, 60_000))
    }

    @Test
    fun normalWalkingCadenceIsWalking() {
        assertEquals(ActivityKind.WALKING, c(90, 60_000))
    }

    @Test
    fun cadenceBoundariesAreInclusive() {
        // 60 步/分算走；10 步/分算没动；两个都是闭区间。
        assertEquals(ActivityKind.WALKING, c(60, 60_000))
        assertEquals(ActivityKind.STILL, c(10, 60_000))
    }

    @Test
    fun grayZoneIsNotGuessed() {
        // 11 步/分：挤地铁、原地挪，说走说坐都会错。
        assertEquals(ActivityKind.UNKNOWN, c(11, 60_000))
        assertEquals(ActivityKind.UNKNOWN, c(59, 60_000))
    }

    @Test
    fun cadenceIsRatePerMinuteNotRawCount() {
        // 120 步 / 2 分钟 = 60 步/分，和 60 步 / 1 分钟一样。
        assertEquals(c(60, 60_000), c(120, 120_000))
    }

    @Test
    fun cyclingHintIsBelieved() {
        assertEquals(ActivityKind.CYCLING, c(0, 60_000, cyclingHint = true))
    }

    @Test
    fun cyclingHintBeatsWalkingHintAndBadWindow() {
        assertEquals(ActivityKind.CYCLING, c(0, 60_000, cyclingHint = true, walkingHint = true))
        assertEquals(ActivityKind.CYCLING, c(-1, 1, cyclingHint = true))
    }

    @Test
    fun walkingHintBeatsTheCadence() {
        // 推着车走路、手插兜，计步器几乎不动，但系统说在走。
        assertEquals(ActivityKind.WALKING, c(3, 60_000, walkingHint = true))
    }

    @Test
    fun labelsAreTheEnginesWords() {
        // 能力库里登记的就是这四个词，改这里等于改协议能写的名字。
        assertEquals("静止", ActivityKind.STILL.label)
        assertEquals("走路", ActivityKind.WALKING.label)
        assertEquals("骑车", ActivityKind.CYCLING.label)
        assertEquals("不确定", ActivityKind.UNKNOWN.label)
    }

    @Test
    fun fourKindsOnly() {
        assertEquals(4, ActivityKind.values().size)
        assertTrue(ActivityKind.values().contains(ActivityKind.UNKNOWN))
        assertFalse(ActivityKind.values().contains(ActivityKind.STILL) && ActivityKind.STILL.label.isEmpty())
    }
}
