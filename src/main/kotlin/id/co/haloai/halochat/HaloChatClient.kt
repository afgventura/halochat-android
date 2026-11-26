package id.co.haloai.halochat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

public class HaloChatClient(
    public val config: HaloChatConfig = HaloChatConfig(),
    tokenProvider: HaloChatTokenProvider,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    internal val transport: Transport = Transport(config.baseUrl, httpClient, tokenProvider)

    @Volatile
    private var pushToken: String? = null

    init {
        transport.tokens.onRefreshed = {
            pushToken?.let { token -> runCatching { postDevice(token) } }
        }
    }

    public suspend fun conversation(): HaloChatConversation =
        transport.decode(HaloChatConversation.serializer(), transport.json(transport.url("conversation"), "GET"))

    public suspend fun latestMessages(limit: Int = 50): HaloChatMessagePage = page(mapOf("limit" to "$limit"))

    public suspend fun messagesAfter(cursor: String, limit: Int = 100): HaloChatMessagePage =
        page(mapOf("after" to cursor, "limit" to "$limit"))

    public suspend fun messagesBefore(cursor: String, limit: Int = 50): HaloChatMessagePage =
        page(mapOf("before" to cursor, "limit" to "$limit"))

    public suspend fun send(text: String, clientMessageId: String = UUID.randomUUID().toString()): HaloChatMessage =
        sendMessage(SendBody(text = text), clientMessageId)

    public suspend fun send(
        text: String,
        extras: JsonObject,
        clientMessageId: String = UUID.randomUUID().toString(),
    ): HaloChatMessage = sendMessage(SendBody(text = text, extras = extras), clientMessageId)

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

    public suspend fun mediaUrl(messageId: String): String =
        resolve(transport.decode(MediaLink.serializer(), transport.json(transport.url("media/$messageId"), "GET")).url)

    internal fun resolve(raw: String): String =
        config.baseUrl.trimEnd('/').toHttpUrl().resolve(raw)?.toString() ?: raw

    public suspend fun registerPushToken(fcmToken: String) {
        pushToken = fcmToken
        postDevice(fcmToken)
    }

    public suspend fun unregisterPushToken(fcmToken: String) {
        pushToken = null
        transport.perform(transport.json(transport.url("devices", mapOf("pushToken" to fcmToken)), "DELETE"))
    }

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
internal data class SendBody(val text: String? = null, val uploadId: String? = null, val extras: JsonObject? = null)

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
