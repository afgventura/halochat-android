package id.co.haloai.halochat.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.co.haloai.halochat.HaloChatClient
import id.co.haloai.halochat.HaloChatException
import id.co.haloai.halochat.HaloChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the chat screen renders. */
internal data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    /** False until the first timeline snapshot (or an error) arrived. */
    val loaded: Boolean = false,
    val title: String? = null,
    val banner: Banner? = null,
    val sessionExpired: Boolean = false,
    val loadingOlder: Boolean = false,
)

internal enum class Banner { RECONNECTING, RATE_LIMITED }

/**
 * Chat screen state that survives rotation: the live timeline, older pages, send responses and
 * the outgoing queue. The timeline itself only runs while the activity is STARTED
 * ([runTimeline] is called from `repeatOnLifecycle`).
 */
internal class ChatViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val widget: HaloChatWidget? = HaloChatWidget.instanceOrNull
    private val client: HaloChatClient? = widget?.client

    private var options: ChatOptions = ChatOptions()
    private var timeline: List<HaloChatMessage> = emptyList()
    private val older = LinkedHashMap<String, HaloChatMessage>()
    private val sent = LinkedHashMap<String, HaloChatMessage>()
    private val pending = PendingQueue()
    private var oldestCursor: String? = null
    private var olderStarted = false
    private var reachedStart = false

    private val _state = MutableStateFlow(ChatUiState(sessionExpired = client == null, loaded = client == null))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Attachment problems and other one-off messages for a toast. */
    private val _notices = Channel<Notice>(Channel.BUFFERED)
    val notices: Flow<Notice> = _notices.receiveAsFlow()

    private var renderJob: Job? = null
    private var started = false

    fun start(options: ChatOptions) {
        if (started) return
        started = true
        this.options = options
        _state.value = _state.value.copy(title = options.title)
        val client = client ?: return
        if (options.title == null) {
            viewModelScope.launch {
                runCatching { client.conversation() }.getOrNull()?.title?.let { title ->
                    _state.value = _state.value.copy(title = title)
                }
            }
        }
        val auto = options.automaticMessage?.trim()
        if (!auto.isNullOrEmpty() && saved.get<Boolean>(KEY_AUTO_SENT) != true) {
            saved[KEY_AUTO_SENT] = true
            send(auto)
        }
    }

    /** The composer prefill, returned once per chat (not again after rotation). */
    fun consumeManualMessage(): String? {
        if (saved.get<Boolean>(KEY_MANUAL_USED) == true) return null
        saved[KEY_MANUAL_USED] = true
        return options.manualMessage
    }

    var pendingCameraUri: String?
        get() = saved[KEY_CAMERA_URI]
        set(value) {
            saved[KEY_CAMERA_URI] = value
        }

    fun notice(notice: Notice) {
        _notices.trySend(notice)
    }

    /** Collects the live timeline until cancelled (screen stopped) or the session expires. */
    suspend fun runTimeline() {
        val client = client ?: return
        if (_state.value.sessionExpired) return
        coroutineScope {
            var job: Job? = null
            job = launch {
                client.timeline(onError = { error ->
                    when (error) {
                        is HaloChatException.Unauthorized -> {
                            expire()
                            job?.cancel()
                        }
                        is HaloChatException.RateLimited -> setBanner(Banner.RATE_LIMITED)
                        else -> setBanner(Banner.RECONNECTING)
                    }
                }).collect { messages ->
                    timeline = ChatRows.retainOutsideWindow(timeline, messages, older, sent)
                    _state.value = _state.value.copy(banner = null, loaded = true)
                    render()
                }
            }
        }
    }

    /** Loads the next older page (scroll to top): latestMessages() first, then messagesBefore(). */
    fun loadOlder() {
        val client = client ?: return
        val current = _state.value
        if (current.loadingOlder || reachedStart || current.sessionExpired || !current.loaded) return
        _state.value = current.copy(loadingOlder = true)
        viewModelScope.launch {
            try {
                val cursor = oldestCursor
                val page = if (!olderStarted || cursor == null) client.latestMessages() else client.messagesBefore(cursor)
                olderStarted = true
                page.messages.forEach { older[it.id] = it }
                oldestCursor = page.oldestCursor
                if (page.messages.isEmpty() || page.oldestCursor == null) reachedStart = true
                // The first (latest) page usually overlaps the live timeline; keep walking back.
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is HaloChatException.Unauthorized) expire()
            } finally {
                _state.value = _state.value.copy(loadingOlder = false)
                render()
            }
        }
    }

    val canLoadOlder: Boolean get() = !reachedStart

    fun send(text: String) {
        val trimmed = text.trim().take(MAX_TEXT)
        if (trimmed.isEmpty()) return
        enqueue(PendingMessage(UUID.randomUUID().toString(), trimmed, null, System.currentTimeMillis()))
    }

    fun sendAttachment(attachment: PendingAttachment) {
        enqueue(PendingMessage(UUID.randomUUID().toString(), null, attachment, System.currentTimeMillis()))
    }

    /** Reads a picked / captured file off the main thread, validates it, and sends it. */
    fun attach(resolver: ContentResolver, uri: Uri, nameOverride: String? = null, mimeOverride: String? = null) {
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { AttachmentReader.read(resolver, uri, nameOverride, mimeOverride) }) {
                is ReadResult.Ok -> sendAttachment(result.attachment)
                is ReadResult.Rejected -> notice(Notice.Attachment(result.problem))
                ReadResult.Failed -> notice(Notice.ReadFailed)
            }
        }
    }

    /** Tap on a failed bubble: same clientMessageId, same bytes. */
    fun retry(clientMessageId: String) {
        val item = pending.markRetrying(clientMessageId) ?: return
        render()
        deliver(item)
    }

    private fun enqueue(item: PendingMessage) {
        pending.add(item)
        render()
        deliver(item)
    }

    private fun deliver(item: PendingMessage) {
        val client = client ?: run {
            pending.markFailed(item.clientMessageId, SendFailure.SESSION_EXPIRED)
            render()
            return
        }
        viewModelScope.launch {
            val result = try {
                Result.success(attempt(client, item))
            } catch (error: HaloChatException.SendInProgress) {
                // The first attempt is still running server-side: wait, re-read, retry once.
                delay(SEND_IN_PROGRESS_WAIT_MS)
                runCatching { client.latestMessages() }.getOrNull()?.messages?.forEach { older[it.id] = it }
                runCatchingNonCancel { attempt(client, item) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.onSuccess { message ->
                sent[message.id] = message
                pending.remove(item.clientMessageId)
            }.onFailure { error ->
                val failure = if (error is HaloChatException.SendInProgress) SendFailure.NETWORK else SendFailures.classify(error)
                pending.markFailed(item.clientMessageId, failure)
                if (failure == SendFailure.SESSION_EXPIRED) expire()
            }
            render()
        }
    }

    private suspend fun attempt(client: HaloChatClient, item: PendingMessage): HaloChatMessage {
        val attachment = item.attachment
        return if (attachment != null) {
            client.sendAttachment(
                bytes = attachment.bytes,
                filename = attachment.filename,
                mimeType = attachment.mimeType,
                caption = item.text,
                clientMessageId = item.clientMessageId,
            )
        } else {
            client.send(item.text.orEmpty(), clientMessageId = item.clientMessageId)
        }
    }

    private fun expire() {
        _state.value = _state.value.copy(sessionExpired = true, banner = null, loaded = true)
    }

    private var bannerClearJob: Job? = null

    /**
     * The timeline only emits when messages change, so a recovered connection is not signalled.
     * While offline the core reports a failure every poll (~5 s); hide the banner once they stop.
     */
    private fun setBanner(banner: Banner) {
        if (_state.value.sessionExpired) return
        _state.value = _state.value.copy(banner = banner, loaded = true)
        bannerClearJob?.cancel()
        bannerClearJob = viewModelScope.launch {
            delay(BANNER_TIMEOUT_MS)
            _state.value = _state.value.copy(banner = null)
        }
    }

    /** Rebuilds rows off the main thread; the newest render wins. */
    private fun render() {
        val messagesSources = arrayOf<Collection<HaloChatMessage>>(older.values.toList(), sent.values.toList(), timeline)
        val queue = pending.snapshot
        val showSystem = options.showSystemMessage
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.Default) {
                ChatRows.build(ChatRows.merge(*messagesSources), queue, showSystem, System.currentTimeMillis())
            }
            _state.value = _state.value.copy(rows = rows)
        }
    }

    private suspend inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    internal sealed class Notice {
        data class Attachment(val problem: AttachmentProblem) : Notice()
        object ReadFailed : Notice()
        object CannotOpen : Notice()
        object DownloadFailed : Notice()
    }

    private companion object {
        const val MAX_TEXT = 4096
        const val SEND_IN_PROGRESS_WAIT_MS = 2_000L
        const val BANNER_TIMEOUT_MS = 12_000L
        const val KEY_AUTO_SENT = "halochat.autoSent"
        const val KEY_MANUAL_USED = "halochat.manualUsed"
        const val KEY_CAMERA_URI = "halochat.cameraUri"
    }
}
