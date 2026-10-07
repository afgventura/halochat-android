package id.co.haloai.halochat.ui

import id.co.haloai.halochat.HaloChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** How a date separator is labelled. */
internal enum class DayLabel { TODAY, YESTERDAY, DATE }

/** One row of the chat list. [key] is stable across updates (RecyclerView diffing). */
internal sealed class ChatRow {
    abstract val key: String

    data class DateHeader(val dayStartMillis: Long, val label: DayLabel) : ChatRow() {
        override val key: String get() = "d:$dayStartMillis"
    }

    data class System(val message: HaloChatMessage) : ChatRow() {
        override val key: String get() = "m:${message.id}"
    }

    /** [showSender]: first business-side bubble of a run (avatar + name when enabled). */
    data class Message(val message: HaloChatMessage, val millis: Long, val showSender: Boolean) : ChatRow() {
        override val key: String get() = "m:${message.id}"
        val mine: Boolean get() = message.sender == HaloChatMessage.Sender.ME
    }

    data class Pending(val pending: PendingMessage) : ChatRow() {
        override val key: String get() = "p:${pending.clientMessageId}"
    }
}

/**
 * Turns server messages (live timeline + older pages + send responses) and the local outgoing
 * queue into display rows. Pure Kotlin: no Android types, unit-tested on the JVM.
 */
internal object ChatRows {
    /**
     * Merges [sources] by message id (a later source wins: pass the live timeline last so its
     * statuses are current) and sorts ascending by createdAt, ties by id.
     */
    fun merge(vararg sources: Collection<HaloChatMessage>): List<HaloChatMessage> {
        val byId = LinkedHashMap<String, HaloChatMessage>()
        for (source in sources) for (message in source) byId[message.id] = message
        val keys = byId.values.associate { it.id to (parseUtcMillis(it.createdAt) ?: 0L) }
        return byId.values.sortedWith(compareBy({ keys[it.id] ?: 0L }, { it.id }))
    }

    /**
     * Applies a new live-timeline snapshot. A message from the [previous] snapshot that is missing
     * from [next] was deleted only when it falls inside [next]'s time range (it is dropped from
     * [older] and [sent] too); one older than that window (the timeline restarted with a fresh,
     * shorter backfill when the chat reopened) is kept in [older], so reopening never leaves a gap.
     * Returns [next].
     */
    fun retainOutsideWindow(
        previous: List<HaloChatMessage>,
        next: List<HaloChatMessage>,
        older: MutableMap<String, HaloChatMessage>,
        sent: MutableMap<String, HaloChatMessage>,
    ): List<HaloChatMessage> {
        val nextIds = next.mapTo(HashSet()) { it.id }
        val windowStart = next.mapNotNull { parseUtcMillis(it.createdAt) }.minOrNull()
        for (message in previous) {
            if (message.id in nextIds) continue
            val millis = parseUtcMillis(message.createdAt) ?: 0L
            if (windowStart != null && millis >= windowStart) {
                older.remove(message.id)
                sent.remove(message.id)
            } else {
                older[message.id] = message
            }
        }
        return next
    }

    /**
     * Builds the rows: date separators per local day, system pills (dropped when [showSystem] is
     * false), message bubbles, then the pending queue at the bottom (in send order).
     */
    fun build(
        messages: List<HaloChatMessage>,
        pending: List<PendingMessage>,
        showSystem: Boolean,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<ChatRow> {
        val rows = ArrayList<ChatRow>(messages.size + pending.size + 8)
        var currentDay: Long? = null
        // True when the previous visible row was a business-side bubble (an agent "run").
        var inAgentRun = false

        fun dayHeader(millis: Long) {
            val day = startOfDay(millis, zone)
            if (day != currentDay) {
                currentDay = day
                rows += ChatRow.DateHeader(day, labelFor(day, nowMillis, zone))
                inAgentRun = false
            }
        }

        for (message in messages) {
            val millis = parseUtcMillis(message.createdAt) ?: 0L
            if (message.senderKind == HaloChatMessage.SenderKind.SYSTEM) {
                if (!showSystem) continue
                dayHeader(millis)
                rows += ChatRow.System(message)
                inAgentRun = false
                continue
            }
            dayHeader(millis)
            val agent = message.sender == HaloChatMessage.Sender.AGENT
            rows += ChatRow.Message(message, millis, showSender = agent && !inAgentRun)
            inAgentRun = agent
        }
        for (item in pending) {
            dayHeader(item.createdAtMillis)
            rows += ChatRow.Pending(item)
            inAgentRun = false
        }
        return rows
    }

    fun startOfDay(millis: Long, zone: TimeZone): Long {
        val calendar = Calendar.getInstance(zone)
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun labelFor(dayStartMillis: Long, nowMillis: Long, zone: TimeZone): DayLabel {
        val today = startOfDay(nowMillis, zone)
        if (dayStartMillis == today) return DayLabel.TODAY
        val yesterday = Calendar.getInstance(zone).apply {
            timeInMillis = today
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        return if (dayStartMillis == yesterday) DayLabel.YESTERDAY else DayLabel.DATE
    }

    /**
     * The server sends UTC `yyyy-MM-ddTHH:mm:ss.SSSZ`. SimpleDateFormat (not java.time) keeps
     * this working on API 21-25 without desugaring.
     */
    fun parseUtcMillis(raw: String): Long? {
        for (pattern in PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            runCatching { format.parse(raw) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    private val PATTERNS = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
}
