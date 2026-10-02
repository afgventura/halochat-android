import id.co.haloai.halochat.HaloChatClient
import id.co.haloai.halochat.HaloChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Minimal HaloChat chat on the JVM: the SDK is plain Kotlin/JVM, so this is the
 * same code an Android app runs. The token comes from YOUR backend after your own
 * login; here a stand-in backend (../../halochat-ios/Example/backend/server.mjs)
 * mints it with the channel's server key.
 *
 *   ./gradlew -p example run --args="sample-user-1 Halo dari Android"
 */
fun main(args: Array<String>) = runBlocking {
    val user = args.getOrElse(0) { "sample-user-1" }
    val text = args.drop(1).joinToString(" ").ifBlank { "Halo dari sample Android" }
    val backend = System.getenv("SAMPLE_BACKEND") ?: "http://127.0.0.1:8791"
    val http = HttpClient.newHttpClient()

    val chat = HaloChatClient(tokenProvider = { forceRefresh ->
        // forceRefresh == true means HaloAI refused the last token: mint a new one.
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

    // Backfill + realtime + polling fallback; wait for the business side to answer.
    val withReply = withTimeout(180_000) {
        chat.timeline().first { messages -> messages.any { it.sender == HaloChatMessage.Sender.AGENT } }
    }
    val reply = withReply.last { it.sender == HaloChatMessage.Sender.AGENT }
    println("reply (${reply.senderKind}): ${reply.text?.take(120)}")
    chat.signOut()
    println("signed out")
}
