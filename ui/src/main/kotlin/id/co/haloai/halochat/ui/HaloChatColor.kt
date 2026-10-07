package id.co.haloai.halochat.ui

import androidx.annotation.ColorInt

/**
 * Chat screen colors. Same 15 setter names and meanings as the Qiscus Multichannel Widget's
 * `QiscusMultichannelWidgetColor`. Left = the business side (AI / human / automation),
 * right = your app user. Every setter returns this, for chaining from Kotlin and Java.
 */
public class HaloChatColor {
    private var navigation: Int = 0xFF0B7A75.toInt()
    private var navigationTitle: Int = 0xFFFFFFFF.toInt()
    private var sendContainer: Int = 0xFF0B7A75.toInt()
    private var sendContainerBackground: Int = 0xFFFFFFFF.toInt()
    private var fieldChatBorder: Int = 0xFFD5D9DD.toInt()
    private var systemEventText: Int = 0xFF5F6B73.toInt()
    private var leftBubble: Int = 0xFFFFFFFF.toInt()
    private var rightBubble: Int = 0xFFD7F0EE.toInt()
    private var leftBubbleText: Int = 0xFF1F2A30.toInt()
    private var rightBubbleText: Int = 0xFF1F2A30.toInt()
    private var timeLabelText: Int = 0xFF5F6B73.toInt()
    private var timeBackground: Int = 0xFFE1E6EA.toInt()
    private var base: Int = 0xFFF0F2F4.toInt()
    private var emptyText: Int = 0xFF5F6B73.toInt()
    private var emptyBackground: Int = 0xFFF0F2F4.toInt()

    /** Header (navigation bar) background; also tints the status bar. */
    public fun setNavigationColor(@ColorInt color: Int): HaloChatColor = apply { navigation = color }

    /** Room title, subtitle and back button. */
    public fun setNavigationTitleColor(@ColorInt color: Int): HaloChatColor = apply { navigationTitle = color }

    /** The send (and attach) icon. */
    public fun setSendContainerColor(@ColorInt color: Int): HaloChatColor = apply { sendContainer = color }

    /** The composer bar background. */
    public fun setSendContainerBackgroundColor(@ColorInt color: Int): HaloChatColor =
        apply { sendContainerBackground = color }

    /** The text field border. */
    public fun setFieldChatBorderColor(@ColorInt color: Int): HaloChatColor = apply { fieldChatBorder = color }

    /** System event pill text and border. */
    public fun setSystemEventTextColor(@ColorInt color: Int): HaloChatColor = apply { systemEventText = color }

    /** Business-side bubble background. */
    public fun setLeftBubbleColor(@ColorInt color: Int): HaloChatColor = apply { leftBubble = color }

    /** App-user bubble background. */
    public fun setRightBubbleColor(@ColorInt color: Int): HaloChatColor = apply { rightBubble = color }

    /** Business-side bubble text. */
    public fun setLeftBubbleTextColor(@ColorInt color: Int): HaloChatColor = apply { leftBubbleText = color }

    /** App-user bubble text. */
    public fun setRightBubbleTextColor(@ColorInt color: Int): HaloChatColor = apply { rightBubbleText = color }

    /** Time labels and date separators text. */
    public fun setTimeLabelTextColor(@ColorInt color: Int): HaloChatColor = apply { timeLabelText = color }

    /** Date separator pill background. */
    public fun setTimeBackgroundColor(@ColorInt color: Int): HaloChatColor = apply { timeBackground = color }

    /** The chat background. */
    public fun setBaseColor(@ColorInt color: Int): HaloChatColor = apply { base = color }

    /** Empty state text. */
    public fun setEmptyTextColor(@ColorInt color: Int): HaloChatColor = apply { emptyText = color }

    /** Empty state background. */
    public fun setEmptyBackgroundColor(@ColorInt color: Int): HaloChatColor = apply { emptyBackground = color }

    @get:ColorInt public val navigationColor: Int get() = navigation
    @get:ColorInt public val navigationTitleColor: Int get() = navigationTitle
    @get:ColorInt public val sendContainerColor: Int get() = sendContainer
    @get:ColorInt public val sendContainerBackgroundColor: Int get() = sendContainerBackground
    @get:ColorInt public val fieldChatBorderColor: Int get() = fieldChatBorder
    @get:ColorInt public val systemEventTextColor: Int get() = systemEventText
    @get:ColorInt public val leftBubbleColor: Int get() = leftBubble
    @get:ColorInt public val rightBubbleColor: Int get() = rightBubble
    @get:ColorInt public val leftBubbleTextColor: Int get() = leftBubbleText
    @get:ColorInt public val rightBubbleTextColor: Int get() = rightBubbleText
    @get:ColorInt public val timeLabelTextColor: Int get() = timeLabelText
    @get:ColorInt public val timeBackgroundColor: Int get() = timeBackground
    @get:ColorInt public val baseColor: Int get() = base
    @get:ColorInt public val emptyTextColor: Int get() = emptyText
    @get:ColorInt public val emptyBackgroundColor: Int get() = emptyBackground
}
