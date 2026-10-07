package id.co.haloai.halochat.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import kotlin.math.roundToInt

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

internal fun View.dp(value: Int): Int = context.dp(value)

internal fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

internal fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

internal fun ImageView.tint(color: Int) {
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color))
}

internal fun isLight(color: Int): Boolean = ColorUtils.calculateLuminance(color) > 0.5

/** Icon for a file card by MIME type. */
internal fun iconFor(mimeType: String?): Int = when {
    mimeType == null -> R.drawable.halochat_ic_file
    mimeType.startsWith("image/") -> R.drawable.halochat_ic_image
    mimeType.startsWith("video/") -> R.drawable.halochat_ic_video
    mimeType.startsWith("audio/") -> R.drawable.halochat_ic_audio
    else -> R.drawable.halochat_ic_file
}
