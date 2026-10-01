package id.co.haloai.halochat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * The HaloChat client for one signed-in app user.
 *
 * ```kotlin
 * val chat = HaloChatClient(tokenProvider = { forceRefresh -> myBackend.haloChatToken(forceRefresh) })
 * lifecycleScope.launch { chat.timeline().collect { render(it) } }
 * chat.send("Halo")
 * ```
 */
public class HaloChatClient(
    public val config: HaloChatConfig = HaloChatConfig(),
    tokenProvider: HaloChatTokenProvider,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    internal val transport: Transport = Transport(config.baseUrl, httpClient, tokenProvider)

    @Volatile
    private var pushToken: String? = null

    init {
        // Every mint is a new server session; re-bind this device's push token to it.
        transport.tokens.onRefreshed = {
            pushToken?.let { token -> runCatching { postDevice(token) } }
        }
    }

    /** The user's conversation with the business. */
    public suspend fun conversation(): HaloChatConversation =
        transport.decode(HaloChatConversation.serializer(), transport.json(transport.url("conversation"), "GET"))

    /** The newest page of history (ascending). */
    public suspend fun latestMessages(limit: Int = 50): HaloChatMessagePage = page(mapOf("limit" to "$limit"))

    /** Messages newer than [cursor]. The server re-sends a few seconds of overlap; merge by id. */
    public suspend fun messagesAfter(cursor: String, limit: Int = 100): HaloChatMessagePage =
        page(mapOf("after" to cursor, "limit" to "$limit"))

    /** Messages older than [cursor] (scroll back). */
    public suspend fun messagesBefore(cursor: String, limit: Int = 50): HaloChatMessagePage =
        page(mapOf("before" to cursor, "limit" to "$limit"))

    /**
     * Sends a text message. [clientMessageId] is the idempotency key: retrying with the SAME
     * id after a timeout can never create a second message. Use a new id for a new message.
     */
    public suspend fun send(text: String, clientMessageId: String = UUID.randomUUID().toString()): HaloChatMessage =
        sendMessage(SendBody(text = text), clientMessageId)

    /** Uploads an attachment and sends it, optionally with a caption. */
    public suspend fun sendAttachment(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        caption: String? = null,
        clientMessageId: String = UUID.randomUUID().toString(),
    ): HaloChatMessage {
        val ticket = transport.decode(
            UploadTicket.serializer(),
            transport.json(
                transport.url("uploads"),
                "POST",
                haloJson.encodeToString(UploadRequest.serializer(), UploadRequest(filename, mimeType, bytes.size.toLong())),
            ),
        )
        val put = Request.Builder().url(resolve(ticket.uploadUrl)).put(bytes.toRequestBody(mimeType.toMediaType()))
        ticket.uploadHeaders.forEach { (name, value) -> put.header(name, value) }
        transport.execute(put.build()).use { response ->
            if (!response.isSuccessful) throw HaloChatException.Server(response.code)
        }
        return sendMessage(SendBody(text = caption, uploadId = ticket.uploadId), clientMessageId)
    }

    /** A short-lived URL for a media message's file. */
    public suspend fun mediaUrl(messageId: String): String =
        resolve(transport.decode(MediaLink.serializer(), transport.json(transport.url("media/$messageId"), "GET")).url)

    /** Storage URLs may be absolute or app-relative (a proxy path); resolve against [HaloChatConfig.baseUrl]. */
    internal fun resolve(raw: String): String =
        config.baseUrl.trimEnd('/').toHttpUrl().resolve(raw)?.toString() ?: raw

    /**
     * Registers this device's FCM token for push while the app is in the background. Call it after
     * every sign-in (`FirebaseMessaging.getInstance().token`) and from `onNewToken`. The SDK remembers
     * it and re-registers by itself whenever it re-mints its HaloChat token.
     */
    public suspend fun registerPushToken(fcmToken: String) {
        pushToken = fcmToken
        postDevice(fcmToken)
    }

    /** Stops push to this device without signing out. */
    public suspend fun unregisterPushToken(fcmToken: String) {
        pushToken = null
        transport.perform(transport.json(transport.url("devices", mapOf("pushToken" to fcmToken)), "DELETE"))
    }

    /**
     * Signs this device out: stops its pushes and expires its HaloChat token on the server.
     * Call it before discarding the client (the Qiscus `clearUser` equivalent).
     */
    public suspend fun signOut() {
        pushToken?.let { token -> runCatching { unregisterPushToken(token) } }
        transport.perform(transport.json(transport.url("session"), "DELETE"))
        transport.tokens.clear()
    }

    private suspend fun postDevice(fcmToken: String) {
        transport.perform(
            transport.json(
                transport.url("devices"),
                "POST",
                haloJson.encodeToString(DeviceRequest.serializer(), DeviceRequest("android", fcmToken)),
            ),
        )
    }

    /**
     * A live, de-duplicated, ascending timeline: history backfill, then realtime socket events,
     * falling back to polling. Collection stops when the collecting coroutine is cancelled.
     *
     * Conflated: a slow collector only sees the newest snapshot. [onError] reports failures the
     * flow recovers from (offline, rate limited) and ones it cannot ([HaloChatException.Unauthorized]:
     * the token provider can no longer mint). Older pages from [messagesBefore] are not part of
     * the flow; merge them by id.
     */
    public fun timeline(initialLimit: Int = 50, onError: ((Throwable) -> Unit)? = null): Flow<List<HaloChatMessage>> =
        Timeline(this, initialLimit, onError).flow().conflate()

    private suspend fun page(query: Map<String, String>): HaloChatMessagePage =
        transport.decode(HaloChatMessagePage.serializer(), transport.json(transport.url("messages", query), "GET"))

    private suspend fun sendMessage(body: SendBody, clientMessageId: String): HaloChatMessage {
        val request = transport.json(transport.url("messages"), "POST", haloJson.encodeToString(SendBody.serializer(), body))
            .newBuilder()
            .header("Idempotency-Key", clientMessageId)
            .build()
        return transport.decode(SentMessage.serializer(), request).message
    }
}

@Serializable
internal data class SendBody(val text: String? = null, val uploadId: String? = null)

@Serializable
internal data class SentMessage(val message: HaloChatMessage)

@Serializable
internal data class UploadRequest(val filename: String, val mimeType: String, val sizeBytes: Long)

@Serializable
internal data class UploadTicket(
    val uploadId: String,
    val uploadUrl: String,
    val uploadHeaders: Map<String, String> = emptyMap(),
)

@Serializable
internal data class MediaLink(val url: String)

@Serializable
internal data class DeviceRequest(val platform: String, val pushToken: String)
