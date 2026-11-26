import id.co.haloai.halochat.HaloChatClient
import id.co.haloai.halochat.HaloChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun main(args: Array<String>) = runBlocking {
    val user = args.getOrElse(0) { "sample-user-1" }
    val text = args.drop(1).joinToString(" ").ifBlank { "Halo dari sample Android" }
    val backend = System.getenv("SAMPLE_BACKEND") ?: "http://127.0.0.1:8791"
    val http = HttpClient.newHttpClient()

    val chat = HaloChatClient(tokenProvider = { forceRefresh ->
        val request = HttpRequest.newBuilder(
            URI("$backend/halochat-token?user=$user&refresh=${if (forceRefresh) 1 else 0}"),
        ).build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        Regex("\"token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: error("backend returned no token")
    })

    val conversation = chat.conversation()
    println("conversation: ${conversation.title} (room ${conversation.roomId})")
    val sent = chat.send(text)
    println("sent: ${sent.id} status=${sent.status}")

    val withReply = withTimeout(180_000) {
        chat.timeline().first { messages -> messages.any { it.sender == HaloChatMessage.Sender.AGENT } }
    }
    val reply = withReply.last { it.sender == HaloChatMessage.Sender.AGENT }
    println("reply (${reply.senderKind}): ${reply.text?.take(120)}")
    chat.signOut()
    println("signed out")
}
