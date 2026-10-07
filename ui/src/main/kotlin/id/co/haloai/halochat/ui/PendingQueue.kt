package id.co.haloai.halochat.ui

/** The bytes of an outgoing attachment, kept so a retry re-sends exactly the same file. */
internal class PendingAttachment(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
)

/**
 * A message the user sent that the server has not confirmed yet. [clientMessageId] is the
 * idempotency key: every retry reuses it, so a retry can never create a second message.
 */
internal data class PendingMessage(
    val clientMessageId: String,
    val text: String?,
    val attachment: PendingAttachment?,
    val createdAtMillis: Long,
    val state: State = State.SENDING,
    val failure: SendFailure? = null,
) {
    enum class State { SENDING, FAILED }
}

/**
 * The outgoing queue: sending → (removed on success | failed → sending on retry). Pure Kotlin.
 * Not thread-safe; the view model touches it from the main thread only.
 */
internal class PendingQueue {
    private val items = LinkedHashMap<String, PendingMessage>()

    val snapshot: List<PendingMessage> get() = items.values.toList()

    operator fun get(clientMessageId: String): PendingMessage? = items[clientMessageId]

    fun add(message: PendingMessage) {
        items[message.clientMessageId] = message
    }

    /** Success: the server message replaces it in the timeline. */
    fun remove(clientMessageId: String): PendingMessage? = items.remove(clientMessageId)

    fun markFailed(clientMessageId: String, failure: SendFailure): PendingMessage? =
        update(clientMessageId) { it.copy(state = PendingMessage.State.FAILED, failure = failure) }

    /** Retry: only a failed item can go back to sending; it keeps its id and bytes. */
    fun markRetrying(clientMessageId: String): PendingMessage? {
        val current = items[clientMessageId] ?: return null
        if (current.state != PendingMessage.State.FAILED) return null
        return update(clientMessageId) { it.copy(state = PendingMessage.State.SENDING, failure = null) }
    }

    private fun update(id: String, change: (PendingMessage) -> PendingMessage): PendingMessage? {
        val current = items[id] ?: return null
        return change(current).also { items[id] = it }
    }
}
