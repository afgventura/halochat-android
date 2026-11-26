package id.co.haloai.halochat.ui

internal class PendingAttachment(
    val bytes: ByteArray,
    val filename: String,
    val mimeType: String,
)

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

internal class PendingQueue {
    private val items = LinkedHashMap<String, PendingMessage>()

    val snapshot: List<PendingMessage> get() = items.values.toList()

    operator fun get(clientMessageId: String): PendingMessage? = items[clientMessageId]

    fun add(message: PendingMessage) {
        items[message.clientMessageId] = message
    }

    fun remove(clientMessageId: String): PendingMessage? = items.remove(clientMessageId)

    fun markFailed(clientMessageId: String, failure: SendFailure): PendingMessage? =
        update(clientMessageId) { it.copy(state = PendingMessage.State.FAILED, failure = failure) }

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
