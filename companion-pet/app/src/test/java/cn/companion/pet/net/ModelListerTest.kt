package cn.companion.pet.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelListerTest {
    private lateinit var server: MockWebServer
    private lateinit var lister: ModelLister

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false); start() }
        lister = ModelLister(OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun urlIsDerivedFromChatEndpoint() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/models", ModelLister.modelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertEquals("https://api.deepseek.com/v1/models", ModelLister.modelsUrl("https://api.deepseek.com/v1/chat/completions"))
        assertNull(ModelLister.modelsUrl("http://insecure.example.com/v1"))
    }

    @Test
    fun parsesOpenAiAndModelsShapes() {
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"),
            ModelLister.parseModels("""{"object":"list","data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"},{"id":"deepseek-chat"}]}"""))
        assertEquals(listOf("gemini-2.0-flash"), ModelLister.parseModels("""{"models":[{"name":"models/gemini-2.0-flash"}]}"""))
        assertTrue(ModelLister.parseModels("not json").isEmpty())
    }

    @Test
    fun visualHints() {
        assertTrue(ModelLister.looksVisual("qwen-vl-plus"))
        assertTrue(ModelLister.looksVisual("gpt-4o-mini"))
        assertFalse(ModelLister.looksVisual("deepseek-chat"))
        assertFalse("'vl' must be a word, not inside another one", ModelLister.looksVisual("qwen-devlin"))
    }

    @Test
    fun fetchesWithBearerAndReportsHttpErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"qwen-vl-plus"},{"id":"qwen-plus"}]}"""))
        val ok = lister.list("https://localhost:${server.port}/v1", "sk-test")
        assertEquals(ModelLister.Result.Success(listOf("qwen-plus", "qwen-vl-plus")), ok)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401))
        val denied = lister.list("https://localhost:${server.port}/v1", "bad")
        assertTrue((denied as ModelLister.Result.Failure).reason.contains("401"))
    }
}
