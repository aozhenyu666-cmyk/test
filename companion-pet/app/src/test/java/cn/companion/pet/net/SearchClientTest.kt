package cn.companion.pet.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索通道：解析真实结果，并保证任何失败都是可识别的降级信号而不是异常。 */
class SearchClientTest {

    @Test
    fun `解析 search 响应为可溯源结果`() {
        val body = """
        {"results":[{"title":"T1","url":"https://a.example/1","snippet":"s",
        "summary":"sum1","site_name":"A站","date":"2026-10-01","rerank_score":2.1}]}
        """.trimIndent()
        val r = DeepSeekSearchClient.parse(body)
        assertTrue(r is SearchResult.Ok)
        val hit = (r as SearchResult.Ok).hits.single()
        assertEquals("https://a.example/1", hit.url)
        assertEquals("A站", hit.siteName)
        assertEquals("sum1", hit.summary)
    }

    @Test
    fun `空结果不是错误，但等于没有素材`() {
        val r = DeepSeekSearchClient.parse("""{"results":[]}""")
        assertTrue(r is SearchResult.Ok)
        assertTrue((r as SearchResult.Ok).hits.isEmpty())
    }

    @Test
    fun `响应结构不对时返回 MALFORMED 而不是抛异常`() {
        val r = DeepSeekSearchClient.parse("""{"unexpected":true}""")
        assertTrue("结构不符必须是可识别失败，不能崩溃", r is SearchResult.Failed)
        assertEquals(SearchFailure.MALFORMED, (r as SearchResult.Failed).kind)
    }

    @Test
    fun `没有结果里缺 url 的条目会被丢弃，因为无法溯源`() {
        val body = """{"results":[{"title":"no url","summary":"x"},{"title":"ok","url":"https://b.example"}]}"""
        val r = DeepSeekSearchClient.parse(body) as SearchResult.Ok
        assertEquals("缺 url 的条目必须被丢弃", 1, r.hits.size)
        assertEquals("https://b.example", r.hits.single().url)
    }

    @Test
    fun `能力自检能把失败翻译成人话`() {
        assertEquals("没配 Key", SearchCapability.levelOf(SearchResult.Failed(SearchFailure.NO_KEY)))
        assertEquals("额度用完", SearchCapability.levelOf(SearchResult.Failed(SearchFailure.QUOTA)))
        assertEquals("可用", SearchCapability.levelOf(SearchResult.Ok(emptyList())))
    }
}
