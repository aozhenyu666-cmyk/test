package com.moling.core

import com.moling.core.atlas.Gap
import com.moling.core.forage.Discovery
import com.moling.core.forage.DiscoveryDraft
import com.moling.core.forage.DiscoveryLine
import com.moling.core.forage.ForageState
import com.moling.core.mind.MindStore
import com.moling.core.web.SearchHit
import com.moling.core.web.SourceState
import com.moling.core.web.WebPage
import com.moling.core.web.WebPort
import com.moling.core.workspace.ArtifactAction
import com.moling.core.workspace.ArtifactActionKind
import com.moling.core.workspace.ArtifactKind
import com.moling.core.workspace.ArtifactStatus
import com.moling.core.workspace.Artifacts
import com.moling.core.workspace.BlockKind
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.Workbench
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十五批 B1：真正联网采蜜（选 query / 两路搜索 / 抓正文进来源库 / 只认来源库编号 / DISCOVERY 落稿）。 */
class V13ForageTest {

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    /** 假的搜索 / 抓取：只回测试摆好的结果，不碰网络；没摆的 URL 一律 NETWORK。 */
    private class FakeWeb(
        private val hits: Map<String, List<SearchHit>> = emptyMap(),
        private val pages: Map<String, WebPage> = emptyMap(),
        private val error: PortError? = null,
    ) : WebPort {
        val searched = mutableListOf<String>()
        val fetched = mutableListOf<String>()

        override suspend fun search(query: String, limit: Int): PortResult<List<SearchHit>> {
            searched += query
            error?.let { return PortResult.Failed(it) }
            return PortResult.Ok((hits[query] ?: emptyList()).take(limit))
        }

        override suspend fun fetch(url: String): PortResult<WebPage> {
            fetched += url
            val p = pages[url] ?: return PortResult.Failed(PortError("NETWORK", "假服务器没有这个页面"))
            return PortResult.Ok(p)
        }

        override suspend fun readFeed(url: String): PortResult<List<com.moling.core.web.FeedItem>> =
            PortResult.Failed(PortError("UNSUPPORTED", "测试不读订阅源"))
    }

    private fun page(url: String, title: String, text: String) =
        WebPage(url, url, title, text, 100, complete = true)

    private fun gap(question: String, weight: Int) = Gap("没依据", "n-1", "判断没有支撑", question, weight)

    private fun workbench(): Workbench = Workbench(MemStore(), null)

    @Test
    fun pickQueryPrefersTheFocusThenTheBiggestGap() {
        assertEquals(
            "怎么写开题报告",
            Discovery.pickQuery("怎么写开题报告", listOf(gap("「别的」——怎么办？", 5))),
        )
        assertEquals(
            "假设 A",
            Discovery.pickQuery(null, listOf(gap("「假设 B」——怎么验证？", 4), gap("「假设 A」——怎么验证？", 5))),
        )
        assertNull(Discovery.pickQuery("  ", emptyList()))
    }

    @Test
    fun queriesAreAtMostTwoDistinctAndCleaned() {
        val qs = Discovery.queries("「假设 A」——按「小样本对照」验证了吗？结果是什么？")
        assertTrue(qs.size in 1..2)
        assertEquals("假设 A", qs[0])
        assertEquals(qs.distinct(), qs)
        assertFalse(qs.any { it.contains("——") || it.contains("「") })
        assertEquals(listOf("只有一个问题"), Discovery.queries("只有一个问题"))
        assertTrue(Discovery.queries("   ").isEmpty())
    }

    @Test
    fun harvestSearchesAtMostTwoRoutesAndFetchesARealBody() = runBlocking {
        val hits = mapOf(
            "路线一" to listOf(
                SearchHit("https://e.com/a", "A"),
                SearchHit("https://e.com/b", "B"),
                SearchHit("https://e.com/c", "C"),
            ),
            "路线二" to listOf(
                SearchHit("https://e.com/a?utm_source=x", "A 重复"),
                SearchHit("https://e.com/d", "D"),
            ),
            "第三路" to listOf(SearchHit("https://e.com/e", "E")),
        )
        val pages = mapOf(
            "https://e.com/a" to page("https://e.com/a", "A", "A 的正文"),
            "https://e.com/b" to page("https://e.com/b", "B", "B 的正文"),
            "https://e.com/c" to page("https://e.com/c", "C", "C 的正文"),
            "https://e.com/d" to page("https://e.com/d", "D", "D 的正文"),
        )
        val web = FakeWeb(hits, pages)
        val h = Discovery.harvest(web, listOf("路线一", "路线二", "第三路"), SourceState(), 10)
        assertEquals("最多两路查询", 2, web.searched.size)
        assertEquals("同一个规范化 URL 只抓一次", 4, web.fetched.size)
        assertEquals(4, h.pages.size)
        assertTrue(h.online)
        assertTrue(h.state.sources.size >= 4)
        h.pages.forEach { p ->
            assertNotNull("每篇正文都要进来源库", h.state.document(p.sourceId))
            assertEquals(p.sourceId, h.state.sources.first { it.id == p.sourceId }.id)
        }
    }

    @Test
    fun notConfiguredNeverProducesFakeResults() = runBlocking {
        val web = FakeWeb(error = PortError("NOT_CONFIGURED", "还没有配置搜索服务"))
        val h = Discovery.harvest(web, Discovery.queries("随便一个问题"), SourceState(), 10)
        assertFalse(h.online)
        assertTrue(h.pages.isEmpty())
        assertEquals("NOT_CONFIGURED", h.errors.first().code)
        assertTrue("一次也不该再试第二路", web.searched.size <= 1)
        assertTrue(web.fetched.isEmpty())
        assertEquals(0, h.state.sources.size)
    }

    @Test
    fun parseKeepsOnlyCitationsThatExistInTheSourceLibrary() {
        val valid = setOf("s-aaaaaaaaaaaa")
        val reply = """
            【支持】多写多改确实有用｜s-aaaaaaaaaaaa
            【反对】只看不写没用｜s-bbbbbbbbbbbb
            【验证】找十个人做两周对照
            【待核】样本是不是太小
        """.trimIndent()
        val d = Discovery.parse(reply, valid)!!
        assertEquals(1, d.lines.count { it.stance == "支持" })
        assertEquals(listOf("s-aaaaaaaaaaaa"), d.lines.first { it.stance == "支持" }.refs)
        assertTrue("编出来的编号丢掉", d.lines.first { it.stance == "反对" }.refs.isEmpty())
        assertTrue(d.lines.any { it.stance == "验证" })
        assertEquals(listOf("样本是不是太小"), d.questions)
        // 只有验证、没有支持 / 反对 → 不算整理过的发现，走兜底
        assertNull(Discovery.parse("【验证】去问问别人", valid))
    }

    @Test
    fun fallbackOnlyListsPagesFetchedThisTime() {
        val pages = listOf(
            com.moling.core.forage.DiscoveryPage("s-aaaaaaaaaaaa", "https://e.com/a", "A", "A 的正文"),
            com.moling.core.forage.DiscoveryPage("s-bbbbbbbbbbbb", "https://e.com/b", "B", "B 的正文"),
        )
        val d = Discovery.fallback(pages)
        assertEquals(listOf("s-aaaaaaaaaaaa", "s-bbbbbbbbbbbb"), d.lines.map { it.refs.single() })
        assertTrue(d.lines.all { it.stance == "摘录" })
    }

    @Test
    fun discoveryArtifactKeepsOnlyLibraryCitationsAndDoesNotAutoClaim() = runBlocking {
        val w = workbench()
        val f = w.start("怎么练写作", "", 1)
        assertTrue(w.addSourceMaterial(f.id, "网页正文：多写多改。", "写作方法", 2) is PortResult.Ok)
        val draft = DiscoveryDraft(
            "",
            listOf(
                DiscoveryLine("支持", "多写多改确实有用", listOf("s-aaaaaaaaaaaa")),
                DiscoveryLine("反对", "只看不写没用", listOf("s-bbbbbbbbbbbb")),
                DiscoveryLine("验证", "找十个人做两周对照"),
            ),
            listOf("样本是不是太小"),
        )
        val a = (w.attachDiscovery(f.id, "联网发现：写作", "h1", draft, listOf("s-aaaaaaaaaaaa"), 3) as PortResult.Ok).value
        assertEquals(ArtifactKind.DISCOVERY, a.kind)
        assertEquals(ArtifactStatus.READY_FOR_REVIEW, a.status)
        assertEquals(listOf("s-aaaaaaaaaaaa"), a.sourceIds)
        assertEquals(listOf("s-aaaaaaaaaaaa"), a.blocks.first { it.markdown.contains("多写多改") }.sourceIds)
        assertEquals(BlockKind.MARKDOWN, a.blocks.first { it.markdown.contains("多写多改") }.kind)
        val oppose = a.blocks.first { it.markdown.contains("只看不写") }
        assertEquals("来源库里没有的编号 → 待核", BlockKind.ASSUMPTION, oppose.kind)
        assertTrue(oppose.sourceIds.isEmpty())
        assertTrue(a.unresolvedQuestions.last().startsWith("网页内容未核实"))
        assertTrue("没采用前不算论断", w.claims(a.id).isEmpty())
        // 他采用支持那一句，才进得了体系图
        val support = a.blocks.first { it.markdown.contains("多写多改") }
        val act = ArtifactAction(
            Artifacts.actionId(a, ArtifactActionKind.ACCEPT_BLOCK, support.id), a.id, a.revision,
            ArtifactActionKind.ACCEPT_BLOCK, support.id, null, null, 4,
        )
        assertTrue(w.apply(act) is PortResult.Ok)
        assertTrue(w.claims(a.id).any { it.contains("多写多改") })
        val md = w.markdown(a.id)
        assertTrue(md.contains("s-aaaaaaaaaaaa"))
        assertTrue("联网发现不写“未联网查证”", md.contains("联网查过"))
        assertNotNull("同一批来源已经查过", w.discovery(f.id, "h1"))
        assertNull(w.discovery(f.id, "h2"))
    }

    @Test
    fun forageStateCarriesBudgetAndLastResult() {
        val st = ForageState(day = "2026-10-22", lastAutoDay = "2026-10-22", lastAutoAt = 10, lastMessage = "联网查回一份")
        val json = Json.encodeToString(st)
        val back = Json.decodeFromString<ForageState>(json)
        assertEquals(st.lastAutoDay, back.lastAutoDay)
        assertEquals(st.lastMessage, back.lastMessage)
        assertEquals(st.budget.maxSearches, back.budget.maxSearches)
    }
}
