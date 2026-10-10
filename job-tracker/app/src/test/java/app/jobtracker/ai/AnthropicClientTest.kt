package app.jobtracker.ai

import app.jobtracker.config.AppConfig
import app.jobtracker.data.FakeApiKeyStore
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.repo.SettingsRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnthropicClientTest {
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val keys = FakeApiKeyStore("sk-secret")
    private var online = true
    private val config = AppConfig.Default.copy(requestTimeoutSeconds = 2)

    private fun client() = AnthropicClient(settings, keys, { online }, config)

    @Before
    fun setUp() = runTest {
        server.start()
        db = inMemoryDatabase()
        settings = SettingsRepository(db.settingsDao(), AppConfig.Default)
        settings.update { it.copy(apiBaseUrl = server.url("/").toString().trimEnd('/')) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun ok(text: String, stopReason: String = "end_turn") = MockResponse().setBody(
        buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("stop_reason", stopReason)
        }.toString(),
    )

    private suspend fun errorOf(block: suspend () -> Unit): ModelError? =
        try {
            block()
            null
        } catch (e: ModelException) {
            e.error
        }

    @Test
    fun sendsMessagesRequestAndReturnsText() = runTest {
        server.enqueue(ok("""{"a":1}"""))
        val schema = buildJsonObject { put("type", "object") }

        val text = client().callModel(ModelRole.ANALYSIS, "系统提示", "用户内容", schema)

        assertEquals("""{"a":1}""", text)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        // 本地地址不是官方接口，不开拒答兜底
        assertNull(request.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("系统提示", body["system"]!!.jsonPrimitive.content)
        assertFalse("fallbacks" in body)
        val outputConfig = body["output_config"]!!.jsonObject
        assertEquals("medium", outputConfig["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", outputConfig["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun draftRoleUsesFastModel() = runTest {
        server.enqueue(ok("好的"))
        client().callModel(ModelRole.DRAFT, "s", "u")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingKeyFailsWithoutRequest() = runTest {
        keys.clear()
        assertEquals(ModelError.NoApiKey, errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun offlineFailsWithoutRequest() = runTest {
        online = false
        assertEquals(ModelError.NoNetwork, errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpErrorCarriesStatusAndMessage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"type":"error","error":{"type":"not_found_error","message":"model: foo"}}"""),
        )
        assertEquals(ModelError.Http(404, "model: foo"), errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
    }

    @Test
    fun refusalAndTruncationAreErrors() = runTest {
        server.enqueue(ok("", stopReason = "refusal"))
        assertEquals(ModelError.Refused, errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
        server.enqueue(ok("""{"a":""", stopReason = "max_tokens"))
        assertEquals(ModelError.InvalidOutput, errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
    }

    @Test
    fun slowServerTimesOut() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(ModelError.Timeout, errorOf { client().callModel(ModelRole.ANALYSIS, "s", "u") })
    }

    @Test
    fun serverFallbackOnlyForOfficialHostAndSupportedModel() {
        val c = AppConfig.Default
        assertTrue(AnthropicClient.useServerFallback("https://api.anthropic.com", "claude-sonnet-5-5", c))
        assertFalse(AnthropicClient.useServerFallback("https://api.anthropic.com", "claude-haiku-5-5", c))
        assertFalse(AnthropicClient.useServerFallback("https://relay.example.com", "claude-sonnet-5-5", c))
    }
}
