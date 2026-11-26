package id.co.haloai.halochat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public data class HaloChatMessage(
    val id: String,
    val text: String? = null,
    val sender: Sender = Sender.AGENT,
    val senderKind: SenderKind = SenderKind.SYSTEM,
    val hasMedia: Boolean = false,
    val mediaType: String? = null,
    val mediaFilename: String? = null,
    val createdAt: String,
    val status: Status? = null,
    val extras: JsonObject? = null,
) {
    @Serializable
    public enum class Sender {
        @SerialName("me") ME,

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

@Serializable
public data class HaloChatConversation(val roomId: String, val title: String? = null)

@Serializable
public data class HaloChatMessagePage(
    val messages: List<HaloChatMessage>,
    val newestCursor: String? = null,
    val oldestCursor: String? = null,
)

public sealed class HaloChatException(message: String) : Exception(message) {
    public class Unauthorized : HaloChatException("HaloChat token refused")

    public class RateLimited(public val retryAfterSeconds: Int) : HaloChatException("Rate limited")

    public class SendInProgress : HaloChatException("Send already in progress")

    public class Rejected(public val status: Int, public val code: String?) :
        HaloChatException("Rejected: $status ${code.orEmpty()}")

    public class Server(public val status: Int) : HaloChatException("Server error: $status")

    public class InvalidResponse : HaloChatException("Invalid response")
}

public fun interface HaloChatTokenProvider {
    public suspend fun token(forceRefresh: Boolean): String
}

public data class HaloChatConfig(
    val baseUrl: String = "https://www.haloai.co.id",
    val realtimeUrl: String = "wss://conn3.haloai.co.id/ws/client",
    val pollIntervalMillis: Long = 5_000,
)
