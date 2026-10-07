package id.co.haloai.halochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.format.DateFormat
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import id.co.haloai.halochat.HaloChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/** What the list needs from the screen. */
internal interface ChatActions {
    fun retry(clientMessageId: String)
    fun openImage(message: HaloChatMessage)
    fun openFile(message: HaloChatMessage)
}

internal class ChatAdapter(
    private val context: Context,
    private val color: HaloChatColor,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
    private val actions: ChatActions,
) : ListAdapter<ChatRow, RecyclerView.ViewHolder>(Diff) {

    var avatarEnabled: Boolean = true
    var senderName: String = ""
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    private val timeFormat = DateFormat.getTimeFormat(context)
    private val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault())

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChatRow.DateHeader -> TYPE_DATE
        is ChatRow.System -> TYPE_SYSTEM
        is ChatRow.Message, is ChatRow.Pending -> TYPE_BUBBLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_DATE -> PillHolder(pill(parent.context, color.timeLabelTextColor, color.timeBackgroundColor, 0))
        TYPE_SYSTEM -> PillHolder(pill(parent.context, color.systemEventTextColor, 0x00000000, color.systemEventTextColor))
        else -> BubbleHolder(parent.context)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ChatRow.DateHeader -> (holder as PillHolder).text.text = when (row.label) {
                DayLabel.TODAY -> context.getString(R.string.halochat_today)
                DayLabel.YESTERDAY -> context.getString(R.string.halochat_yesterday)
                DayLabel.DATE -> dateFormat.format(Date(row.dayStartMillis))
            }
            is ChatRow.System -> (holder as PillHolder).text.text = row.message.text.orEmpty()
            is ChatRow.Message -> (holder as BubbleHolder).bind(row)
            is ChatRow.Pending -> (holder as BubbleHolder).bind(row.pending)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is BubbleHolder) holder.unbind()
    }

    private fun pill(context: Context, textColor: Int, background: Int, border: Int): FrameLayout {
        val frame = FrameLayout(context)
        frame.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        frame.setPadding(0, context.dp(8), 0, context.dp(8))
        val text = TextView(context).apply {
            setTextColor(textColor)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(4))
            this.background = rounded(background, context.dp(12).toFloat(), if (border != 0) context.dp(1) else 0, border)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        frame.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return frame
    }

    private class PillHolder(frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val text: TextView = frame.getChildAt(0) as TextView
    }

    /** One chat bubble, incoming or outgoing, server-confirmed or pending. Built in code. */
    private inner class BubbleHolder(context: Context) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val root = itemView as LinearLayout
        private val senderRow = LinearLayout(context)
        private val avatar = TextView(context)
        private val sender = TextView(context)
        private val bubbleRow = LinearLayout(context)
        private val avatarSpacer = View(context)
        private val bubble = LinearLayout(context)
        private val image = ImageView(context)
        private val fileCard = LinearLayout(context)
        private val fileIcon = ImageView(context)
        private val fileName = TextView(context)
        private val fileHint = TextView(context)
        private val text = TextView(context)
        private val time = TextView(context)
        private val status = ImageView(context)
        private val failure = TextView(context)
        private var imageJob: Job? = null
        private var boundImageId: String? = null
        private val maxBubbleWidth = (context.resources.displayMetrics.widthPixels * 0.75).toInt()
        private val imageWidth = minOf(context.dp(240), maxBubbleWidth - context.dp(16))

        init {
            root.orientation = LinearLayout.VERTICAL
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            root.setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))

            senderRow.orientation = LinearLayout.HORIZONTAL
            senderRow.gravity = Gravity.CENTER_VERTICAL
            senderRow.setPadding(0, context.dp(6), 0, context.dp(2))
            avatar.apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                background = circle(color.navigationColor)
                setTextColor(color.navigationTitleColor)
            }
            senderRow.addView(avatar, LinearLayout.LayoutParams(context.dp(28), context.dp(28)))
            sender.apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color.timeLabelTextColor)
                setPadding(context.dp(8), 0, 0, 0)
                maxLines = 1
            }
            senderRow.addView(sender)
            root.addView(senderRow)

            bubbleRow.orientation = LinearLayout.HORIZONTAL
            bubbleRow.addView(avatarSpacer, LinearLayout.LayoutParams(context.dp(36), 1))
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(4))
            bubbleRow.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(bubbleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.adjustViewBounds = false
            image.contentDescription = context.getString(R.string.halochat_image)
            bubble.addView(image, LinearLayout.LayoutParams(imageWidth, imageWidth * 3 / 4).apply { topMargin = context.dp(2) })

            fileCard.orientation = LinearLayout.HORIZONTAL
            fileCard.gravity = Gravity.CENTER_VERTICAL
            fileCard.setPadding(0, context.dp(4), 0, context.dp(4))
            fileCard.addView(fileIcon, LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
            val fileText = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), 0, 0, 0)
            }
            fileName.apply {
                textSize = 14f
                maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                maxWidth = maxBubbleWidth - context.dp(70)
            }
            fileHint.textSize = 12f
            fileText.addView(fileName)
            fileText.addView(fileHint)
            fileCard.addView(fileText)
            bubble.addView(fileCard)

            text.apply {
                textSize = 15f
                maxWidth = maxBubbleWidth - context.dp(20)
                setTextIsSelectable(false)
            }
            bubble.addView(text)

            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            time.textSize = 11f
            footer.addView(time)
            footer.addView(status, LinearLayout.LayoutParams(context.dp(15), context.dp(15)).apply { marginStart = context.dp(4) })
            bubble.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2)
            })

            failure.apply {
                textSize = 12f
                setTextColor(0xFFD93025.toInt())
                setPadding(context.dp(4), context.dp(2), context.dp(4), 0)
                gravity = Gravity.END
            }
            root.addView(failure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun bind(row: ChatRow.Message) {
            val message = row.message
            val mine = row.mine
            layout(mine, showSender = !mine && row.showSender && avatarEnabled)
            val mediaType = message.mediaType
            when {
                message.hasMedia && mediaType != null && mediaType.startsWith("image/") -> showImage(message)
                message.hasMedia -> showFile(iconFor(mediaType), message.mediaFilename) { actions.openFile(message) }
                else -> hideAttachment()
            }
            val body = message.text
            when {
                !body.isNullOrEmpty() -> showText(body, mine)
                !message.hasMedia -> showText(context.getString(R.string.halochat_unsupported_message), mine, italic = true)
                else -> text.visibility = View.GONE
            }
            time.text = timeFormat.format(Date(row.millis))
            if (mine) {
                status.visibility = View.VISIBLE
                val read = message.status == HaloChatMessage.Status.READ
                status.setImageResource(if (read) R.drawable.halochat_ic_check_double else R.drawable.halochat_ic_check)
                status.tint(if (read) READ_TINT else color.timeLabelTextColor)
                status.contentDescription = context.getString(if (read) R.string.halochat_status_read else R.string.halochat_status_sent)
            } else {
                status.visibility = View.GONE
            }
            failure.visibility = View.GONE
            itemView.setOnClickListener(null)
            itemView.isClickable = false
        }

        fun bind(item: PendingMessage) {
            layout(mine = true, showSender = false)
            val attachment = item.attachment
            if (attachment != null) {
                showFile(iconFor(attachment.mimeType), attachment.filename, onClick = null)
            } else {
                hideAttachment()
            }
            val body = item.text
            if (!body.isNullOrEmpty()) showText(body, mine = true) else text.visibility = View.GONE
            time.text = timeFormat.format(Date(item.createdAtMillis))
            status.visibility = View.VISIBLE
            if (item.state == PendingMessage.State.FAILED) {
                status.setImageResource(R.drawable.halochat_ic_error)
                status.tint(0xFFD93025.toInt())
                status.contentDescription = context.getString(R.string.halochat_status_failed)
                failure.visibility = View.VISIBLE
                failure.text = failureText(item.failure)
                itemView.setOnClickListener { actions.retry(item.clientMessageId) }
            } else {
                status.setImageResource(R.drawable.halochat_ic_clock)
                status.tint(color.timeLabelTextColor)
                status.contentDescription = context.getString(R.string.halochat_status_sending)
                failure.visibility = View.GONE
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            }
        }

        fun unbind() {
            imageJob?.cancel()
            imageJob = null
            boundImageId = null
            image.setImageDrawable(null)
        }

        private fun failureText(failure: SendFailure?): String {
            val reason = when (failure) {
                SendFailure.TOO_LARGE -> R.string.halochat_error_too_large
                SendFailure.UNSUPPORTED_TYPE -> R.string.halochat_error_unsupported_type
                SendFailure.RATE_LIMITED -> R.string.halochat_failed_rate_limited
                SendFailure.SESSION_EXPIRED -> R.string.halochat_failed_session
                else -> R.string.halochat_failed_network
            }
            return context.getString(reason) + " " + context.getString(R.string.halochat_failed_tap_retry)
        }

        private fun layout(mine: Boolean, showSender: Boolean) {
            val gravity = if (mine) Gravity.END else Gravity.START
            root.gravity = gravity
            (bubbleRow.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            (failure.layoutParams as LinearLayout.LayoutParams).gravity = gravity
            senderRow.visibility = if (showSender) View.VISIBLE else View.GONE
            if (showSender) {
                avatar.text = senderName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                sender.text = senderName
            }
            avatarSpacer.visibility = if (!mine && avatarEnabled) View.VISIBLE else View.GONE
            val radius = context.dp(12).toFloat()
            bubble.background = rounded(if (mine) color.rightBubbleColor else color.leftBubbleColor, radius)
            val textColor = if (mine) color.rightBubbleTextColor else color.leftBubbleTextColor
            text.setTextColor(textColor)
            text.setLinkTextColor(textColor)
            fileName.setTextColor(textColor)
            fileHint.setTextColor(color.timeLabelTextColor)
            fileIcon.tint(if (mine) color.sendContainerColor else color.navigationColor)
            time.setTextColor(color.timeLabelTextColor)
        }

        private fun showText(body: String, mine: Boolean, italic: Boolean = false) {
            text.visibility = View.VISIBLE
            text.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
            text.text = body
            if (!italic) Linkify.addLinks(text, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS)
            text.setTextColor(if (mine) color.rightBubbleTextColor else color.leftBubbleTextColor)
        }

        private fun hideAttachment() {
            unbind()
            image.visibility = View.GONE
            fileCard.visibility = View.GONE
            fileCard.setOnClickListener(null)
        }

        private fun showFile(icon: Int, filename: String?, onClick: (() -> Unit)?) {
            unbind()
            image.visibility = View.GONE
            fileCard.visibility = View.VISIBLE
            fileIcon.setImageResource(icon)
            fileName.text = filename ?: context.getString(R.string.halochat_attachment)
            fileHint.visibility = if (onClick != null) View.VISIBLE else View.GONE
            fileHint.text = context.getString(R.string.halochat_tap_to_open)
            if (onClick != null) fileCard.setOnClickListener { onClick() } else fileCard.setOnClickListener(null)
            fileCard.isClickable = onClick != null
        }

        private fun showImage(message: HaloChatMessage) {
            fileCard.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.setOnClickListener { actions.openImage(message) }
            if (boundImageId == message.id && image.drawable != null) return
            unbind()
            boundImageId = message.id
            val cached = media.cachedThumbnail(message.id)
            if (cached != null) {
                setBitmap(cached)
                return
            }
            image.setImageDrawable(null)
            image.setBackgroundColor(color.timeBackgroundColor)
            setImageSize(imageWidth * 3 / 4)
            val id = message.id
            imageJob = scope.launch {
                val bitmap = runCatching { media.thumbnail(id, THUMBNAIL_PX) }.getOrNull()
                if (boundImageId != id) return@launch
                if (bitmap != null) {
                    setBitmap(bitmap)
                } else {
                    image.scaleType = ImageView.ScaleType.CENTER
                    image.setImageResource(R.drawable.halochat_ic_image)
                    image.tint(color.timeLabelTextColor)
                }
            }
        }

        private fun setBitmap(bitmap: Bitmap) {
            image.background = null
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            ImageViewCompat.setImageTintList(image, null)
            val ratio = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
            setImageSize((imageWidth * ratio).toInt().coerceIn(imageWidth / 2, imageWidth * 4 / 3))
            image.setImageBitmap(bitmap)
        }

        private fun setImageSize(height: Int) {
            val params = image.layoutParams
            if (params.height != height) {
                params.height = height
                image.layoutParams = params
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ChatRow>() {
        override fun areItemsTheSame(oldItem: ChatRow, newItem: ChatRow): Boolean = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: ChatRow, newItem: ChatRow): Boolean = oldItem == newItem
    }

    private companion object {
        const val TYPE_DATE = 1
        const val TYPE_SYSTEM = 2
        const val TYPE_BUBBLE = 3
        const val THUMBNAIL_PX = 720
        const val READ_TINT = 0xFF34B7F1.toInt()
    }
}
