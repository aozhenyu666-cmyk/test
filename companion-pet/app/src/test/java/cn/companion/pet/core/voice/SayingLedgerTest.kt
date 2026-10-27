package cn.companion.pet.core.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 说过的不能重样：改标点、改空格都不能绕过。 */
class SayingLedgerTest {

    @Test
    fun `一模一样的话被拦`() {
        val l = SayingLedger()
        l.record("你刚才切了好几个应用，在找什么？", now = 1L)
        assertTrue(l.isTooSimilar("你刚才切了好几个应用，在找什么？"))
    }

    @Test
    fun `只改标点和空格也拦得住`() {
        val l = SayingLedger()
        l.record("你刚才切了好几个应用，在找什么？", now = 1L)
        assertTrue("改标点不能绕过", l.isTooSimilar("你刚才切了好几个应用 在找什么"))
    }

    @Test
    fun `换了一件完全不同的事就放行`() {
        val l = SayingLedger()
        l.record("你刚才切了好几个应用，在找什么？", now = 1L)
        assertFalse(l.isTooSimilar("我今天读到一段关于时间尺度的东西"))
    }

    @Test
    fun `只说一半相同也不算重复`() {
        val l = SayingLedger()
        l.record("你刚才一分钟切了好几个应用，是在找东西吗", now = 1L)
        assertFalse("主题相同但措辞不同应当允许", l.isTooSimilar("今天这本书里有个说法挺反直觉"))
    }

    @Test
    fun `最近说过的话只保留容量上限`() {
        val l = SayingLedger(capacity = 3)
        repeat(5) { l.record("第 $it 句完全不同的话", now = it.toLong()) }
        assertTrue("容量上限生效", l.recent(10).size <= 3)
    }
}
