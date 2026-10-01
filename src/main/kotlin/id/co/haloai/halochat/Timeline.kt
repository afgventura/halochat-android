package id.co.haloai.halochat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Merges messages by id and keeps them ascending by createdAt (ties by id).
 * The server re-sends a short overlap on polls and a socket event can repeat a
 * message already fetched, so every source funnels through here.
 */
internal class MessageMerger {
    private val byId = LinkedHashMap<String, HaloChatMessage>()
    private val sortKey = HashMap<String, Long>()
    var newestCursor: String? = null
        private set

    /** Returns true when the timeline changed. */
    fun merge(messages: List<HaloChatMessage>): Boolean {
        var changed = false
        for (message in messages) {
            if (byId[message.id] != message) {
                byId[message.id] = message
                // Parsed once here (not per comparison); an unparseable date sorts first.
                sortKey[message.id] = parseUtcMillis(message.createdAt) ?: 0L
                changed = true
            }
        }
        return changed
    }

    fun remove(id: String): Boolean {
        sortKey.remove(id)
        return byId.remove(id) != null
    }

    fun advance(cursor: String?) {
        if (cursor != null) newestCursor = cursor
    }

    val ordered: List<HaloChatMessage>
        get() = byId.values.sortedWith(compareBy({ sortKey[it.id] ?: 0L }, { it.id }))

    internal companion object {
        /**
         * The server always sends UTC `yyyy-MM-ddTHH:mm:ss.SSSZ`. SimpleDateFormat instead of
         * java.time keeps this working on API 21-25 without core-library desugaring.
         */
        fun parseUtcMillis(raw: String): Long? {
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                runCatching { format.parse(raw) }.getOrNull()?.let { return it.time }
            }
            return null
        }
    }
}

/** One realtime frame from `/ws/client`. */
internal sealed class RealtimeEvent {
    object Authenticated : RealtimeEvent()
    data class Message(val message: HaloChatMessage) : RealtimeEvent()
    data class Deleted(val id: String) : RealtimeEvent()
    object Other : RealtimeEvent()

    companion object {
        fun decode(text: String): RealtimeEvent {
            val frame: JsonObject = runCatching { haloJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Other
            return when (frame["type"]?.jsonPrimitive?.content) {
                "authenticated" -> Authenticated
                "message_deleted" -> frame["id"]?.jsonPrimitive?.content?.let(::Deleted) ?: Other
                "message" -> frame["message"]
                    ?.let { runCatching { haloJson.decodeFromJsonElement(HaloChatMessage.serializer(), it) }.getOrNull() }
                    ?.let(::Message) ?: Other
                else -> Other
            }
        }
    }
}

/** Drives a live timeline: backfill, then socket; on socket loss, catch up and poll, then retry the socket. */
internal class Timeline(
    private val client: HaloChatClient,
    private val initialLimit: Int,
    private val onError: ((Throwable) -> Unit)?,
) {
    private val merger = MessageMerger()
    private val lock = Mutex()
    private val pollMillis = client.config.pollIntervalMillis.coerceAtLeast(1_000)

    fun flow(): Flow<List<HaloChatMessage>> = channelFlow {
        try {
            val page = client.latestMessages(initialLimit)
            lock.withLock { merger.merge(page.messages); merger.advance(page.newestCursor) }
            send(lock.withLock { merger.ordered })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error)
        }
        var backoff = 1
        while (isActive) {
            catchUp { send(it) }
            val connected = runSocket { send(it) }
            if (!isActive) break
            if (connected) backoff = 1
            repeat(backoff) {
                delay(pollMillis)
                catchUp { send(it) }
            }
            backoff = (backoff * 2).coerceAtMost(12)
        }
    }

    /** Reports an error; honours the server's Retry-After before the caller continues. */
    private suspend fun report(error: Throwable) {
        onError?.invoke(error)
        if (error is HaloChatException.RateLimited) delay(error.retryAfterSeconds * 1_000L)
    }

    /** Fetches everything newer than the cursor, page by page (bounded). */
    private suspend fun catchUp(emit: suspend (List<HaloChatMessage>) -> Unit) {
        repeat(MAX_CATCH_UP_PAGES) {
            val cursor = lock.withLock { merger.newestCursor }
            val page = try {
                if (cursor != null) client.messagesAfter(cursor, CATCH_UP_PAGE_SIZE) else client.latestMessages(initialLimit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report(error)
                return
            }
            val changed = lock.withLock {
                val didChange = merger.merge(page.messages)
                merger.advance(page.newestCursor)
                if (didChange) merger.ordered else null
            }
            changed?.let { emit(it) }
            // `after` pages include an overlap window, so "full" means strictly new rows filled it.
            if (page.messages.size < CATCH_UP_PAGE_SIZE) return
        }
    }

    private suspend fun apply(event: RealtimeEvent): List<HaloChatMessage>? = lock.withLock {
        val changed = when (event) {
            is RealtimeEvent.Message -> merger.merge(listOf(event.message))
            is RealtimeEvent.Deleted -> merger.remove(event.id)
            else -> false
        }
        if (changed) merger.ordered else null
    }

    /** Returns whether the socket authenticated before it ended. */
    private suspend fun runSocket(emit: suspend (List<HaloChatMessage>) -> Unit): Boolean {
        val token = try {
            client.transport.tokens.token()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error)
            return false
        }
        val frames = Channel<String>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Int>()
        val socket = client.transport.http.newWebSocket(
            Request.Builder().url(client.config.realtimeUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildJsonObject { put("type", "auth"); put("token", token) }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Answer with a normal close; echoing the server's code can be a reserved one.
                    webSocket.close(1000, null)
                    closed.complete(code)
                    frames.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(-1)
                    frames.close()
                }
            },
        )
        var authenticated = false
        try {
            for (text in frames) {
                val event = RealtimeEvent.decode(text)
                if (event == RealtimeEvent.Authenticated) {
                    authenticated = true
                    // Anything sent between the last poll and subscription is fetched here.
                    catchUp(emit)
                }
                apply(event)?.let { emit(it) }
            }
        } finally {
            socket.cancel()
        }
        if (closed.isCompleted && closed.getCompleted() == 4001) {
            // Token refused/expired: re-mint once (single-flight) for the next attempt.
            runCatching { client.transport.tokens.refresh(rejected = token) }
        }
        return authenticated
    }

    private companion object {
        const val CATCH_UP_PAGE_SIZE = 100
        const val MAX_CATCH_UP_PAGES = 10
    }
}
