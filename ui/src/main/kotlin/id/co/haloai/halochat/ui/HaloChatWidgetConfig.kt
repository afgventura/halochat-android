package id.co.haloai.halochat.ui

import androidx.annotation.DrawableRes

/**
 * Widget-wide settings (the Qiscus `QiscusMultichannelWidgetConfig` equivalent). Setters return
 * this, for chaining from Kotlin and Java.
 */
public class HaloChatWidgetConfig {
    private var notificationsEnabled: Boolean = true
    private var smallIcon: Int = 0
    private var channelName: String? = null

    /** When false, [HaloChatWidget.handleNotification] never posts anything. Default true. */
    public fun setEnableNotification(enabled: Boolean): HaloChatWidgetConfig = apply { notificationsEnabled = enabled }

    /**
     * Small (status bar) icon for chat notifications: a white-on-transparent drawable. Default: the
     * SDK's chat-bubble icon.
     */
    public fun setNotificationIcon(@DrawableRes icon: Int): HaloChatWidgetConfig = apply { smallIcon = icon }

    /** Name of the notification channel shown in system settings. Default: "HaloChat". */
    public fun setNotificationChannelName(name: String): HaloChatWidgetConfig = apply { channelName = name }

    public val enableNotification: Boolean get() = notificationsEnabled

    /** 0 = the SDK default icon. */
    @get:DrawableRes public val notificationIcon: Int get() = smallIcon

    /** Null = the localized default. */
    public val notificationChannelName: String? get() = channelName
}

/** Header subtitle mode (Qiscus `RoomSubtitle`). */
public enum class RoomSubtitle {
    /** A localized "Customer Service". */
    ENABLED,

    /** No subtitle. */
    DISABLED,

    /** The text passed to [ChatBuilder.setRoomSubtitle]. */
    EDITABLE,
}

/** Whether business-side bubbles show an avatar and sender name (Qiscus `Avatar`). */
public enum class Avatar { ENABLED, DISABLED }
