package id.co.haloai.halochat.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Full-screen image viewer. Fetches a fresh media URL every time it opens. */
internal class HaloChatImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        val widget = HaloChatWidget.instanceOrNull
        if (messageId == null || widget == null) {
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.halochat_image)
        }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.halochat_ic_close)
            tint(Color.WHITE)
            background = null
            contentDescription = getString(R.string.halochat_close)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        val metrics = resources.displayMetrics
        val maxPx = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtMost(MAX_SIDE_PX)
        lifecycleScope.launch {
            val bitmap = runCatching { widget.media.fullImage(messageId, maxPx) }.getOrNull()
            progress.visibility = android.view.View.GONE
            if (bitmap == null) {
                Toast.makeText(this@HaloChatImageActivity, R.string.halochat_error_download, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                image.setImageBitmap(bitmap)
            }
        }
    }

    companion object {
        private const val EXTRA_MESSAGE_ID = "id.co.haloai.halochat.ui.MESSAGE_ID"
        private const val MAX_SIDE_PX = 2048

        fun intent(context: Context, messageId: String): Intent =
            Intent(context, HaloChatImageActivity::class.java).putExtra(EXTRA_MESSAGE_ID, messageId)
    }
}
