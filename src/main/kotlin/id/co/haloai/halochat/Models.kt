package id.co.haloai.halochat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One message of the end user's conversation. Mirrors the server's `ClientMessage`. */
@Serializable
public data class HaloChatMessage(
    val id: String,
    val text: String? = null,
    // Defaults + coerceInputValues: a value added by a newer server never fails a page.
    val sender: Sender = Sender.AGENT,
    val senderKind: SenderKind = SenderKind.SYSTEM,
    val hasMedia: Boolean = false,
    val mediaType: String? = null,
    val mediaFilename: String? = null,
    /** ISO-8601 instant. */
    val createdAt: String,
    val status: Status? = null,
) {
    @Serializable
    public enum class Sender {
        /** The signed-in app user. */
        @SerialName("me") ME,

        /** The business side: AI, a human agent, or an automation. */
        @SerialName("agent") AGENT,
    }

    @Serializable
    public enum class SenderKind {
        @SerialName("customer") CUSTOMER,
        @SerialName("ai") AI,
        @SerialName("human") HUMAN,
        @SerialName("system") SYSTEM,
    }

    @Serializable
    public enum class Status {
        @SerialName("sent") SENT,
        @SerialName("delivered") DELIVERED,
        @SerialName("read") READ,
    }
}

/** The end user's single conversation with the business. */
@Serializable
public data class HaloChatConversation(val roomId: String, val title: String? = null)

/** One page of history, ascending by time. Cursors are opaque; pass them back verbatim. */
@Serializable
public data class HaloChatMessagePage(
    val messages: List<HaloChatMessage>,
    val newestCursor: String? = null,
    val oldestCursor: String? = null,
)

/** Errors surfaced by the SDK. */
public sealed class HaloChatException(message: String) : Exception(message) {
    /** The token was refused even after one refresh through the token provider. */
    public class Unauthorized : HaloChatException("HaloChat token refused")

    /** Too many requests; retry after [retryAfterSeconds]. */
    public class RateLimited(public val retryAfterSeconds: Int) : HaloChatException("Rate limited")

    /** A send with this idempotency key is still in flight; re-read before retrying. */
    public class SendInProgress : HaloChatException("Send already in progress")

    /** The server rejected the request (4xx other than 401/409/429). */
    public class Rejected(public val status: Int, public val code: String?) :
        HaloChatException("Rejected: $status ${code.orEmpty()}")

    /** Transient server failure (5xx). */
    public class Server(public val status: Int) : HaloChatException("Server error: $status")

    /** The response could not be decoded. */
    public class InvalidResponse : HaloChatException("Invalid response")
}

/**
 * Supplies HaloChat client tokens (`hct_…`), minted by YOUR backend with the channel's server key.
 * `forceRefresh` is true after the server refused the current token: mint a new one.
 * Never embed the server key (`hck_…`) in the app.
 */
public fun interface HaloChatTokenProvider {
    public suspend fun token(forceRefresh: Boolean): String
}

/** Where the SDK talks to. Defaults are HaloAI production. */
public data class HaloChatConfig(
    val baseUrl: String = "https://www.haloai.co.id",
    val realtimeUrl: String = "wss://conn3.haloai.co.id/ws/client",
    /** Foreground polling interval used when the realtime socket is unavailable. */
    val pollIntervalMillis: Long = 5_000,
)
