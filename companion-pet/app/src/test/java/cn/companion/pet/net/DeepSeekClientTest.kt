package cn.companion.pet.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeepSeekClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        client = DeepSeekClient(
            OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun endpoint() = "https://localhost:${server.port}/v1"

    private fun req(key: String = "sk-test", maxTokens: Int = 150, timeout: Long = 2_000) = ChatRequest(
        endpoint = endpoint(),
        model = "deepseek-chat",
        apiKey = key,
        messages = listOf(ChatMessage(ChatRole.SYSTEM, "s"), ChatMessage(ChatRole.USER, "u")),
        maxTokens = maxTokens,
        timeoutMillis = timeout,
    )

    private fun ok(content: String) = MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(content))}}}]}""")

    @Test
    fun endpointPolicy() {
        assertEquals("https://api.deepseek.com/chat/completions", EndpointPolicy.normalize(" https://api.deepseek.com/ "))
        assertEquals("https://x.com:8443/v1/chat/completions", EndpointPolicy.normalize("https://x.com:8443/v1"))
        assertEquals("https://x.com/v1/chat/completions", EndpointPolicy.normalize("https://x.com/v1/chat/completions/"))
        listOf("http://x.com", "https://u:p@x.com", "https://x.com?a=1", "https://x.com?", "https://x.com#", "", "https://").forEach {
            assertThrows(IllegalArgumentException::class.java) { EndpointPolicy.normalize(it) }
        }
    }

    @Test
    fun sendsOpenAiJsonWithBearer() = runBlocking {
        server.enqueue(ok("{\"hook_text\":\"嗨\"}"))
        val r = client.complete(req(maxTokens = 2000))
        assertEquals(ChatResult.Success("{\"hook_text\":\"嗨\"}"), r)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals("2000", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun noKeyNoAuthHeader() = runBlocking {
        server.enqueue(ok("x"))
        client.complete(req(key = " "))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun httpErrorsMapped() = runBlocking {
        for (code in listOf(401, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("secret error"))
            assertEquals(ChatResult.Failure(ChatFailureKind.HTTP, code), client.complete(req()))
        }
    }

    @Test
    fun redirectNotFollowed() = runBlocking {
        val other = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
        assertEquals(ChatResult.Failure(ChatFailureKind.HTTP, 302), client.complete(req()))
        assertEquals(0, other.requestCount)
        assertEquals(1, server.requestCount)
        other.shutdown()
    }

    @Test
    fun malformedResponses() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        server.enqueue(MockResponse().setBody("x".repeat((DeepSeekClient.MAX_BODY_BYTES + 10).toInt())))
        repeat(4) { assertEquals(ChatResult.Failure(ChatFailureKind.MALFORMED_RESPONSE), client.complete(req())) }
    }

    @Test
    fun timeoutAndNoRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(3, TimeUnit.SECONDS))
        val r = client.complete(req(timeout = 300))
        assertEquals(ChatResult.Failure(ChatFailureKind.TIMEOUT), r)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun disconnectIsNetwork() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val r = client.complete(req())
        assertTrue(r is ChatResult.Failure && (r.kind == ChatFailureKind.NETWORK || r.kind == ChatFailureKind.TIMEOUT))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidConfig() = runBlocking {
        assertEquals(ChatResult.Failure(ChatFailureKind.INVALID_CONFIG), client.complete(req().copy(endpoint = "http://x")))
        assertEquals(ChatResult.Failure(ChatFailureKind.INVALID_CONFIG), client.complete(req().copy(model = " ")))
        assertEquals(ChatResult.Failure(ChatFailureKind.INVALID_CONFIG), client.complete(req().copy(messages = emptyList())))
        assertEquals(ChatResult.Failure(ChatFailureKind.INVALID_CONFIG), client.complete(req().copy(maxTokens = 0)))
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        // 延迟要明显短于 MockWebServer.shutdown() 的 5 秒等待，否则 tearDown 偶尔抛 IOException（CI 上出现过）。
        // 取消发生在服务端收到请求后立刻，2 秒足够让请求保持挂起。
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))
        var finished = false
        val job = async { client.complete(req(timeout = 10_000)).also { finished = true } }
        withTimeout(2_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(finished)
    }

    @Test
    fun sendsImageContentBlocksForVision() = runBlocking {
        server.enqueue(ok("看到了"))
        val request = req().copy(messages = listOf(
            ChatMessage(ChatRole.USER, "看图", listOf("data:image/jpeg;base64,AAAA")),
        ))
        client.complete(request)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }
}