package id.co.haloai.halochat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle

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

public class ChatBuilder internal constructor(private val widget: HaloChatWidget) {
    private var options = ChatOptions()

    public fun setRoomTitle(title: String): ChatBuilder = apply { options = options.copy(title = title) }

    @JvmOverloads
    public fun setRoomSubtitle(mode: RoomSubtitle, text: String? = null): ChatBuilder =
        apply { options = options.copy(subtitle = mode, subtitleText = text) }

    public fun setShowSystemMessage(show: Boolean): ChatBuilder = apply { options = options.copy(showSystemMessage = show) }

    public fun setAvatar(avatar: Avatar): ChatBuilder = apply { options = options.copy(avatar = avatar) }

    public fun automaticSendMessage(text: String): ChatBuilder = apply { options = options.copy(automaticMessage = text) }

    public fun manualSendMessage(text: String): ChatBuilder = apply { options = options.copy(manualMessage = text) }

    public fun startChat(context: Context) {
        widget.lastOptions = options.withoutOneShotMessages()
        context.startActivity(HaloChatActivity.intent(context, options))
    }
}
