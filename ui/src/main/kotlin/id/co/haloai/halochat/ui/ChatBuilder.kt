package id.co.haloai.halochat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** What one chat screen shows; travels to [HaloChatActivity] as intent extras. */
internal data class ChatOptions(
    val title: String? = null,
    val subtitle: RoomSubtitle = RoomSubtitle.ENABLED,
    val subtitleText: String? = null,
    val showSystemMessage: Boolean = true,
    val avatar: Avatar = Avatar.ENABLED,
    val automaticMessage: String? = null,
    val manualMessage: String? = null,
) {
    fun writeTo(intent: Intent): Intent = intent
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_SUBTITLE, subtitle.name)
        .putExtra(EXTRA_SUBTITLE_TEXT, subtitleText)
        .putExtra(EXTRA_SHOW_SYSTEM, showSystemMessage)
        .putExtra(EXTRA_AVATAR, avatar.name)
        .putExtra(EXTRA_AUTO, automaticMessage)
        .putExtra(EXTRA_MANUAL, manualMessage)

    /** Options for a chat opened from a notification: the last opened ones, minus one-shot messages. */
    fun withoutOneShotMessages(): ChatOptions = copy(automaticMessage = null, manualMessage = null)

    companion object {
        private const val EXTRA_TITLE = "id.co.haloai.halochat.ui.TITLE"
        private const val EXTRA_SUBTITLE = "id.co.haloai.halochat.ui.SUBTITLE"
        private const val EXTRA_SUBTITLE_TEXT = "id.co.haloai.halochat.ui.SUBTITLE_TEXT"
        private const val EXTRA_SHOW_SYSTEM = "id.co.haloai.halochat.ui.SHOW_SYSTEM"
        private const val EXTRA_AVATAR = "id.co.haloai.halochat.ui.AVATAR"
        private const val EXTRA_AUTO = "id.co.haloai.halochat.ui.AUTO_MESSAGE"
        private const val EXTRA_MANUAL = "id.co.haloai.halochat.ui.MANUAL_MESSAGE"

        fun from(extras: Bundle?): ChatOptions {
            if (extras == null) return ChatOptions()
            return ChatOptions(
                title = extras.getString(EXTRA_TITLE),
                subtitle = runCatching { RoomSubtitle.valueOf(extras.getString(EXTRA_SUBTITLE).orEmpty()) }
                    .getOrDefault(RoomSubtitle.ENABLED),
                subtitleText = extras.getString(EXTRA_SUBTITLE_TEXT),
                showSystemMessage = extras.getBoolean(EXTRA_SHOW_SYSTEM, true),
                avatar = runCatching { Avatar.valueOf(extras.getString(EXTRA_AVATAR).orEmpty()) }
                    .getOrDefault(Avatar.ENABLED),
                automaticMessage = extras.getString(EXTRA_AUTO),
                manualMessage = extras.getString(EXTRA_MANUAL),
            )
        }
    }
}

/**
 * Configures and opens the chat screen (Qiscus `initiateChat()` builder).
 *
 * ```kotlin
 * HaloChatWidget.instance.initiateChat()
 *     .setRoomTitle("Bantuan")
 *     .setRoomSubtitle(RoomSubtitle.EDITABLE, "Biasanya membalas dalam beberapa menit")
 *     .startChat(context)
 * ```
 */
public class ChatBuilder internal constructor(private val widget: HaloChatWidget) {
    private var options = ChatOptions()

    /** Header title. Default: the conversation title (the channel label). */
    public fun setRoomTitle(title: String): ChatBuilder = apply { options = options.copy(title = title) }

    /** Header subtitle: [RoomSubtitle.ENABLED] (default, "Customer Service"), DISABLED, or EDITABLE with [text]. */
    @JvmOverloads
    public fun setRoomSubtitle(mode: RoomSubtitle, text: String? = null): ChatBuilder =
        apply { options = options.copy(subtitle = mode, subtitleText = text) }

    /** Show system events (`senderKind == system`) as centered pills. Default true. */
    public fun setShowSystemMessage(show: Boolean): ChatBuilder = apply { options = options.copy(showSystemMessage = show) }

    /** Avatar + sender name on the first business-side bubble of each run. Default ENABLED. */
    public fun setAvatar(avatar: Avatar): ChatBuilder = apply { options = options.copy(avatar = avatar) }

    /** Sent once, as the user, when the chat opens. */
    public fun automaticSendMessage(text: String): ChatBuilder = apply { options = options.copy(automaticMessage = text) }

    /** Pre-filled in the composer when the chat opens; the user decides whether to send it. */
    public fun manualSendMessage(text: String): ChatBuilder = apply { options = options.copy(manualMessage = text) }

    /**
     * Opens [HaloChatActivity]. Call [HaloChatWidget.setUser] first; without a user the screen
     * shows "Session expired".
     */
    public fun startChat(context: Context) {
        widget.lastOptions = options.withoutOneShotMessages()
        context.startActivity(HaloChatActivity.intent(context, options))
    }
}
