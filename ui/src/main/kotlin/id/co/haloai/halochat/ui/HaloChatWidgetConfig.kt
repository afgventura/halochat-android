package id.co.haloai.halochat.ui

import androidx.annotation.DrawableRes

public class HaloChatWidgetConfig {
    private var notificationsEnabled: Boolean = true
    private var smallIcon: Int = 0
    private var channelName: String? = null

    public fun setEnableNotification(enabled: Boolean): HaloChatWidgetConfig = apply { notificationsEnabled = enabled }

    public fun setNotificationIcon(@DrawableRes icon: Int): HaloChatWidgetConfig = apply { smallIcon = icon }

    public fun setNotificationChannelName(name: String): HaloChatWidgetConfig = apply { channelName = name }

    public val enableNotification: Boolean get() = notificationsEnabled

    @get:DrawableRes public val notificationIcon: Int get() = smallIcon

    public val notificationChannelName: String? get() = channelName
}

public enum class RoomSubtitle {
    ENABLED,

    DISABLED,

    EDITABLE,
}

public enum class Avatar { ENABLED, DISABLED }
