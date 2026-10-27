package cn.companion.pet.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说过的不能重样：改标点、改空格、改语气词、补尾注都不能绕过；
 * 换了说法说另一件事则必须放行。
 *
 * 这里的测试刻意把**中间重叠区**也钉住（见 [口径被上下两条卡在中间]），
 * 否则把 `isTooSimilar` 换成「归一化后严格相等」、或把 threshold 改成 0.05，
 * 只要样本全是 1.0 / 0.0 的重合度，测试都会全绿——那样阈值其实一点没被约束。
 */
class SayingLedgerTest {

    private val base = "你刚才一分钟切了好几个应用，是在找东西吗"

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

    /** 语气词是「换个说法」最省事的一条缝，必须堵死。 */
    @Test
    fun `只改语气词也拦得住`() {
        val l = SayingLedger()
        l.record(base, now = 1L)
        val particleSwapped = "啊，你刚才一分钟切了好几个应用呢，是在找东西吧"
        assertTrue("实测归一化后完全一致（重合度 1.0）", l.isTooSimilar(particleSwapped))
        assertTrue("归一化把语气词滤干净了", TextNormalize.of(base) == TextNormalize.of(particleSwapped))
    }

    @Test
    fun `只剩语气词和标点的内容归一化后为空`() {
        assertEquals("", TextNormalize.of("嗯"))
        assertEquals("", TextNormalize.of("……"))
        assertEquals("", TextNormalize.of("啊吧呢呀嘛"))
        assertTrue(TextNormalize.isEmpty("  ？!。  "))
        assertFalse(TextNormalize.isEmpty("嗯我今天有点累"))
    }

    /**
     * 加尾注不能把相似度「稀释」到阈值以下。
     *
     * 记录句与探针句：探针 = 记录句原样 + 「我看到你来回切换了」。
     * 实测 |A|=14、|B|=23、|∩|=14 → 对称 Jaccard 只有 0.59（< 0.72，旧口径会**放行**），
     * 而「交集 / 较短者」是 1.0，取大值后判定仍是 1.0，拦得住。
     */
    @Test
    fun `加尾注稀释也绕不过去`() {
        val recorded = "你刚才切了好几个应用，在找什么？"
        val padded = "你刚才切了好几个应用，在找什么？我看到你来回切换了"
        val l = SayingLedger()
        l.record(recorded, now = 1L)

        val symmetric = TextNormalize.jaccard(TextNormalize.bigrams(recorded), TextNormalize.bigrams(padded))
        val judged = TextNormalize.overlap(recorded, padded)

        assertEquals("对称口径在加尾注后会掉到阈值以下（这正是缺陷）", 0.5909, symmetric, 0.005)
        assertEquals("新口径不被稀释", 1.0, judged, 0.005)
        assertTrue("加尾注仍必须判为重复", l.isTooSimilar(padded))
    }

    /**
     * 用「高重叠必拦 + 中重叠必放行」两条断言把 threshold 卡在中间。
     *
     * 实测（归一化 bigram）：
     * - 高重叠对：M = |∩|/min = 0.8333（对称 Jaccard 仅 0.5625）→ 必须拦
     * - 中重叠对：M = 0.6471，J = 0.4783 → 在 0.72 下必须放行
     *
     * 因此：换成「严格相等」→ 高重叠那条挂；threshold 改到 0.05 → 中重叠那条挂；
     * 去掉「交集/较短者」的取大逻辑 → 高重叠那条会落到 0.55 而被放行，也挂。
     */
    @Test
    fun `口径被上下两条卡在中间`() {
        // 高重叠：同一句式，只换了个动词/少了修饰。归一化后并不相等。
        val highPair = "刚才切了好几个应用，在找东西吗"
        // 中重叠：主题还在聊「切应用」，但句式已经重写。
        val midPair = "刚才你切了好几个应用，是不是在找什么东西"

        assertFalse("高重叠样本不能是严格相等，否则卡不住严格相等实现",
            TextNormalize.of(base) == TextNormalize.of(highPair))

        val high = TextNormalize.overlap(base, highPair)
        val mid = TextNormalize.overlap(base, midPair)
        assertEquals("高重叠实测 0.8333", 0.8333, high, 0.005)
        assertEquals("中重叠实测 0.6471", 0.6471, mid, 0.005)
        assertTrue("高重叠必须落在阈值之上", high >= SayingLedger.DEFAULT_THRESHOLD)
        assertTrue("中重叠必须落在阈值之下", mid < SayingLedger.DEFAULT_THRESHOLD)

        val l = SayingLedger()
        l.record(base, now = 1L)
        assertTrue("约 0.85 的重叠必须判为重复", l.isTooSimilar(highPair))
        assertFalse("约 0.61 的重叠在 0.72 下必须放行", l.isTooSimilar(midPair))
    }

    /**
     * 真正「部分重合」的样本：实测重合度 0.3750（落在 0.3–0.5）。
     *
     * 旧测试名叫「只说一半相同也不算重复」，但一条公共 bigram 都没有（0.0），
     * 实际只是「完全不同 → 放行」的重复用例，没测到「部分重合该不该拦」。
     */
    @Test
    fun `只重合一部分时不算重复`() {
        val partiallyShared = "几个应用切来切去，是不是在找什么东西"
        val shared = TextNormalize.bigrams(base).intersect(TextNormalize.bigrams(partiallyShared))
        assertTrue("这条样本必须真有公共 bigram，否则测不到部分重合", shared.isNotEmpty())

        val ratio = TextNormalize.overlap(base, partiallyShared)
        assertEquals("实测 0.3750", 0.3750, ratio, 0.005)
        assertTrue("样本应落在 0.3–0.5 区间", ratio in 0.30..0.50)

        val l = SayingLedger()
        l.record(base, now = 1L)
        assertFalse("部分重合按新口径不判重复", l.isTooSimilar(partiallyShared))
    }

    @Test
    fun `换了一件完全不同的事就放行`() {
        val l = SayingLedger()
        l.record("你刚才切了好几个应用，在找什么？", now = 1L)
        assertFalse(l.isTooSimilar("我今天读到一段关于时间尺度的东西"))
    }

    /** 纯标点/纯语气词没有语义，记下来也拦不住任何东西，不该占容量。 */
    @Test
    fun `归一化后为空的内容不记账`() {
        val l = SayingLedger(capacity = 3)
        l.record("……", now = 1L)
        l.record("嗯", now = 2L)
        l.record("啊啊啊？？", now = 3L)
        assertEquals("空内容不该入库", 0, l.recent(10).size)

        l.record("这是一句真话", now = 4L)
        assertEquals("真内容才入库", 1, l.recent(10).size)
    }

    @Test
    fun `最近说过的话只保留容量上限`() {
        val l = SayingLedger(capacity = 3)
        repeat(5) { l.record("第 $it 句完全不同的话", now = it.toLong()) }
        assertEquals("容量上限必须正好生效，实现成 1 也该挂", 3, l.recent(10).size)
        assertEquals(listOf("第 2 句完全不同的话", "第 3 句完全不同的话", "第 4 句完全不同的话"), l.recent(10))
    }
}
