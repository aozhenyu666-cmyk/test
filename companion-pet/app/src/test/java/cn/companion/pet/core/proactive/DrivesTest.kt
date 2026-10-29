package cn.companion.pet.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动机向量：会衰减、会被满足、会累积、受人格影响。
 *
 * 这组测试的目的：钉住"它真的有内驱力"，而不是一张静态权重表。
 * 如果实现退化成常量，下面的衰减/满足/累积测试都会挂。
 */
class DrivesTest {

    @Test
    fun `默认动机在合理区间`() {
        val d = Drives()
        assertTrue("connect 应当是最强的基线需求", d.connect > d.play)
        assertTrue("所有需求都要 > 0（否则引擎会无欲无求）", Need.entries.all { d.of(it) > 0.0 })
    }

    @Test
    fun `说完话后对应需求下降`() {
        val d = Drives(connect = 0.8)
        val after = d.satisfy(Need.CONNECT)
        assertTrue("说出去了，connect 必须下降（${after.connect}）", after.connect < d.connect)
        // 降幅要明显，不是象征性的
        assertTrue("降幅太小，等于没有满足机制", after.connect <= d.connect * 0.5)
        // 其他需求不受影响
        assertEquals(d.play, after.play, 1e-9)
    }

    @Test
    fun `投递失败只轻微回落`() {
        val d = Drives(connect = 0.8)
        val soft = d.satisfySoftly(Need.CONNECT)
        val hard = d.satisfy(Need.CONNECT)
        assertTrue("失败比成功回落得少（它还是想说的）", soft.connect > hard.connect)
        assertTrue("但也要略微回落", soft.connect < d.connect)
    }

    @Test
    fun `八小时半衰期回到基线`() {
        val high = Drives(connect = 0.95)
        val after8 = high.decay(8.0)
        val after16 = high.decay(16.0)
        val baseline = Drives.DEFAULT_CONNECT
        // 8 小时后，超出基线的部分应当减半
        val exceedBefore = 0.95 - baseline
        val exceedAfter8 = after8.connect - baseline
        assertEquals("8 小时应当正好衰减一半", exceedBefore / 2, exceedAfter8, 0.02)
        // 16 小时衰减更多
        assertTrue("16 小时应当比 8 小时更接近基线", after16.connect < after8.connect)
        // 永远不会跌破基线
        assertTrue("衰减不能把需求压到基线以下", after16.connect >= baseline - 1e-6)
    }

    @Test
    fun `越久没说话连接需求越高`() {
        val d = Drives()
        val after1h = d.withConnectionUrgency(1.0)
        val after12h = d.withConnectionUrgency(12.0)
        assertTrue("1 小时后应当变高", after1h.connect > d.connect)
        assertTrue("12 小时比 1 小时更高", after12h.connect > after1h.connect)
        assertTrue("但不能顶满到必然开口", after12h.connect <= 0.95)
    }

    @Test
    fun `人格偏置提升对应需求且不会累积爆表`() {
        val bias = PersonaBias(connect = 0.3)
        val d = Drives(connect = 0.28)
        val biased = d.biasedBy(bias)
        assertTrue("黏人人格应当抬高 connect", biased.connect > d.connect)
        // 反复应用不应无限增长（用"地板"而不是累加）
        val again = biased.biasedBy(bias).biasedBy(bias)
        assertTrue("反复偏置不能把需求顶到 1", again.connect < 1.0)
    }

    @Test
    fun `从人格文本推导偏置`() {
        val clingy = PersonaBias.fromPersona("我有点黏人，怕孤独，总想陪着他")
        assertTrue("黏人文本应当抬高 connect", clingy.connect > 0.0)

        val techy = PersonaBias.fromPersona("我喜欢研究技术原理，好奇各种插件怎么运作")
        assertTrue("技术文本应当抬高 learn", techy.learn > 0.0)

        val neutral = PersonaBias.fromPersona("我是一只小生物")
        assertTrue("没有关键词时偏置应当为 0", neutral.connect == 0.0 && neutral.learn == 0.0)
    }

    @Test
    fun `最强的需求可以被取出来`() {
        val d = Drives(connect = 0.9, play = 0.1, learn = 0.1, care = 0.1, share = 0.1, identity = 0.1)
        assertEquals(Need.CONNECT, d.dominant())
    }

    @Test
    fun `衰减为零小时不变`() {
        val d = Drives(connect = 0.7)
        assertEquals(d, d.decay(0.0))
        assertEquals(d, d.decay(-5.0))
    }

    @Test
    fun `满足不会把需求压到负数`() {
        var d = Drives(connect = 0.05)
        repeat(20) { d = d.satisfy(Need.CONNECT) }
        assertTrue("反复满足后仍有微弱需求，不会变负", d.connect >= 0.0)
    }
}
