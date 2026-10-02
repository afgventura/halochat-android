package id.co.haloai.halochat

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HaloChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HaloChatClient
    private val minted = AtomicInteger(0)
    private val forcedRefreshes = AtomicInteger(0)

    private val messageJson =
        """{"id":"m1","text":"halo","sender":"me","senderKind":"customer","hasMedia":false,"mediaType":null,"mediaFilename":null,"createdAt":"2026-09-29T08:00:00.123Z","status":"read"}"""

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = HaloChatClient(
            config = HaloChatConfig(baseUrl = server.url("/").toString()),
            tokenProvider = { forceRefresh ->
                if (forceRefresh) forcedRefreshes.incrementAndGet()
                if (forceRefresh || minted.get() == 0) minted.incrementAndGet()
                "hct_token_${minted.get()}"
            },
        )
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun sendSetsIdempotencyKeyAndDecodesMessage() = runTest {
        server.enqueue(ok("""{"status":"ok","data":{"message":$messageJson}}"""))
        val message = client.send("halo", clientMessageId = "key-12345678")

        assertEquals("m1", message.id)
        assertEquals(HaloChatMessage.Sender.ME, message.sender)
        assertEquals(HaloChatMessage.Status.READ, message.status)
        val request = server.takeRequest()
        assertEquals("/api/client/inApp/v1/messages", request.path)
        assertEquals("POST", request.method)
        assertEquals("key-12345678", request.getHeader("Idempotency-Key"))
        assertEquals("Bearer hct_token_1", request.getHeader("Authorization"))
        assertEquals("halo", haloJson.parseToJsonElement(request.body.readUtf8()).jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun concurrentFirstCallsMintOneToken() = runTest {
        // A real backend takes time to mint; every call it receives is a new server session.
        val calls = AtomicInteger(0)
        val gate = TokenGate(HaloChatTokenProvider { _ ->
            calls.incrementAndGet()
            delay(50)
            "hct_first"
        })
        val tokens = (1..3).map { async { gate.token() } }.awaitAll()

        assertEquals(setOf("hct_first"), tokens.toSet())
        assertEquals(1, calls.get(), "concurrent first callers must share one mint, not each start a session")
    }

    @Test
    fun timelineBackfillsThenCatchesUpByPollingWhenTheSocketIsDown() = runBlocking {
        fun message(id: String, second: Int) =
            """{"id":"$id","text":"t","sender":"agent","senderKind":"ai","hasMedia":false,"mediaType":null,"mediaFilename":null,"createdAt":"2026-09-29T08:00:0$second.000Z","status":null}"""
        fun page(messages: List<String>, newest: String) =
            ok("""{"status":"ok","data":{"messages":[${messages.joinToString(",")}],"newestCursor":"$newest","oldestCursor":"c0"}}""")
        server.enqueue(page(listOf(message("m1", 1)), "c1")) // backfill
        server.enqueue(page(listOf(message("m1", 1)), "c1")) // catch-up: overlap only
        server.enqueue(page(listOf(message("m1", 1), message("m2", 2)), "c2")) // poll while the socket is down
        val offline = HaloChatClient(
            config = HaloChatConfig(
                baseUrl = server.url("/").toString(),
                realtimeUrl = "ws://127.0.0.1:1/ws/client",
                pollIntervalMillis = 1_000,
            ),
            tokenProvider = { "hct_token" },
        )

        val timeline = withTimeout(10_000) { offline.timeline().first { it.size == 2 } }

        assertEquals(listOf("m1", "m2"), timeline.map { it.id }, "polling must deliver the new message once, in order")
        server.takeRequest() // backfill
        val catchUp = server.takeRequest()
        assertEquals("c1", catchUp.requestUrl?.queryParameter("after"), "catch-up must resume from the backfill cursor")
    }

    @Test
    fun unauthorizedRefreshesTokenOnceAndRetries() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_token"}"""))
        server.enqueue(ok("""{"status":"ok","data":{"roomId":"r1","title":"Acme Support"}}"""))

        assertEquals(HaloChatConversation("r1", "Acme Support"), client.conversation())
        assertEquals("Bearer hct_token_1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer hct_token_2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun secondUnauthorizedSurfacesError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<HaloChatException.Unauthorized> { client.conversation() }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitConflictAndRejectionMapping() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        assertEquals(7, assertFailsWith<HaloChatException.RateLimited> { client.latestMessages() }.retryAfterSeconds)

        server.enqueue(MockResponse().setResponseCode(409))
        assertFailsWith<HaloChatException.SendInProgress> { client.send("x") }

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"status":"error","error":"invalid_text"}"""))
        assertEquals("invalid_text", assertFailsWith<HaloChatException.Rejected> { client.send("") }.code)
    }

    @Test
    fun historyCursorPassThrough() = runTest {
        server.enqueue(ok("""{"status":"ok","data":{"messages":[$messageJson],"newestCursor":"c2","oldestCursor":"c1"}}"""))
        assertEquals("c2", client.messagesAfter("c1").newestCursor)
        assertEquals("c1", server.takeRequest().requestUrl?.queryParameter("after"))
    }

    @Test
    fun pushTokenRegistration() = runTest {
        server.enqueue(ok("""{"status":"ok","data":{}}"""))
        client.registerPushToken("fcm-token-1")
        val request = server.takeRequest()
        assertEquals("/api/client/inApp/v1/devices", request.path)
        val body = haloJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("android", body["platform"]?.jsonPrimitive?.content)
        assertEquals("fcm-token-1", body["pushToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun mergerDedupesOrdersAndApplies() {
        val merger = MessageMerger()
        val early = HaloChatMessage("b", "1", HaloChatMessage.Sender.ME, HaloChatMessage.SenderKind.CUSTOMER, createdAt = "2026-09-29T08:00:00Z")
        val late = HaloChatMessage("a", "2", HaloChatMessage.Sender.AGENT, HaloChatMessage.SenderKind.AI, createdAt = "2026-09-29T08:00:05.500Z")

        assertTrue(merger.merge(listOf(late, early)))
        assertFalse(merger.merge(listOf(early)), "an overlapping re-delivery is not a change")
        assertEquals(listOf("b", "a"), merger.ordered.map { it.id })
        assertTrue(merger.merge(listOf(early.copy(status = HaloChatMessage.Status.READ))))
        assertEquals(HaloChatMessage.Status.READ, merger.ordered.first().status)
        assertTrue(merger.remove("a"))
        assertEquals(listOf("b"), merger.ordered.map { it.id })
    }

    @Test
    fun realtimeFrameDecoding() {
        assertEquals(RealtimeEvent.Authenticated, RealtimeEvent.decode("""{"type":"authenticated","roomId":"r"}"""))
        assertEquals(RealtimeEvent.Deleted("m9"), RealtimeEvent.decode("""{"type":"message_deleted","id":"m9"}"""))
        assertEquals("m1", assertIs<RealtimeEvent.Message>(RealtimeEvent.decode("""{"type":"message","message":$messageJson}""")).message.id)
        assertEquals(RealtimeEvent.Other, RealtimeEvent.decode("""{"type":"heartbeat"}"""))
        assertEquals(RealtimeEvent.Other, RealtimeEvent.decode("not json"))
    }

    @Test
    fun storageUrlsResolveAgainstBaseUrl() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/bff/object-storage/upload?token=x", client.resolve("/bff/object-storage/upload?token=x"))
        assertEquals("https://cdn.example/a.png", client.resolve("https://cdn.example/a.png"))
    }

    @Test
    fun concurrent401sRefreshTheTokenOnce() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        repeat(2) { server.enqueue(ok("""{"status":"ok","data":{"roomId":"r1","title":null}}""")) }
        listOf(async { client.conversation() }, async { client.conversation() }).awaitAll()
        assertEquals(1, forcedRefreshes.get(), "two concurrent 401s must mint one new token")
    }

    @Test
    fun unknownEnumValuesDoNotFailThePage() = runTest {
        val future = """{"id":"m2","text":"x","sender":"bot_v2","senderKind":"robot","createdAt":"2026-09-29T08:00:00.000Z","status":"seen"}"""
        server.enqueue(ok("""{"status":"ok","data":{"messages":[$future],"newestCursor":"c","oldestCursor":"c"}}"""))
        val message = client.latestMessages().messages.single()
        assertEquals(HaloChatMessage.Sender.AGENT, message.sender)
        assertEquals(HaloChatMessage.SenderKind.SYSTEM, message.senderKind)
        assertNull(message.status)
    }

    @Test
    fun signOutUnregistersThenExpiresTheSession() = runTest {
        repeat(3) { server.enqueue(ok("""{"status":"ok","data":{}}""")) }
        client.registerPushToken("fcm-token-1234567890")
        client.signOut()
        val calls = List(3) { server.takeRequest().let { "${it.method} ${it.requestUrl?.encodedPath}" } }
        assertEquals(
            listOf(
                "POST /api/client/inApp/v1/devices",
                "DELETE /api/client/inApp/v1/devices",
                "DELETE /api/client/inApp/v1/session",
            ),
            calls,
        )
    }

    @Test
    fun pushTokenIsReRegisteredAfterAForcedRefresh() = runTest {
        server.enqueue(ok("""{"status":"ok","data":{}}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(ok("""{"status":"ok","data":{}}"""))
        server.enqueue(ok("""{"status":"ok","data":{"roomId":"r1","title":null}}"""))
        client.registerPushToken("fcm-token-1234567890")
        client.conversation()
        val paths = List(4) { server.takeRequest().let { "${it.requestUrl?.encodedPath} ${it.getHeader("Authorization")}" } }
        assertTrue(paths.contains("/api/client/inApp/v1/devices Bearer hct_token_2"), paths.toString())
    }

    @Test
    fun utcTimestampsParseWithoutJavaTime() {
        assertEquals(1_790_690_400_123L, MessageMerger.parseUtcMillis("2026-09-29T14:00:00.123Z"))
        assertEquals(1_790_690_400_000L, MessageMerger.parseUtcMillis("2026-09-29T14:00:00Z"))
        assertNull(MessageMerger.parseUtcMillis("not a date"))
    }
}
