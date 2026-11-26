package id.co.haloai.halochat.example

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import id.co.haloai.halochat.HaloChatConfig
import id.co.haloai.halochat.ui.HaloChatWidget
import id.co.haloai.halochat.ui.RoomSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private val widget get() = HaloChatWidget.instance
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val user = EditText(this).apply { setText("sample-user-1"); hint = "externalUserId" }
        status = TextView(this)
        val signIn = Button(this).apply {
            text = "Sign in"
            setOnClickListener {
                val userId = user.text.toString().trim().ifEmpty { "sample-user-1" }
                val base = BuildConfig.HALO_BASE_URL
                val coreConfig = if (base.isEmpty()) {
                    HaloChatConfig()
                } else {
                    HaloChatConfig(baseUrl = base, realtimeUrl = base.replaceFirst("http", "ws") + "/ws/client")
                }
                widget.setUser({ forceRefresh -> mintToken(userId, forceRefresh) }, coreConfig)
                refresh()
            }
        }
        val open = Button(this).apply {
            text = "Open chat"
            setOnClickListener {
                widget.initiateChat()
                    .setRoomTitle("HaloChat sample")
                    .setRoomSubtitle(RoomSubtitle.ENABLED)
                    .setShowSystemMessage(true)
                    .startChat(this@MainActivity)
            }
        }
        val signOut = Button(this).apply {
            text = "Sign out"
            setOnClickListener { widget.clearUser(Runnable { refresh() }) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(user)
            addView(signIn)
            addView(open)
            addView(signOut)
            addView(status)
        })
        refresh()
        if (widget.isLoggedIn()) widget.tapNotification(this, intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (widget.isLoggedIn()) widget.tapNotification(this, intent)
    }

    private fun refresh() {
        status.text = if (widget.isLoggedIn()) "Signed in" else "Signed out"
    }

    private suspend fun mintToken(userId: String, forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val url = URL(
            "${BuildConfig.TOKEN_BACKEND}/halochat-token?user=${URLEncoder.encode(userId, "UTF-8")}" +
                "&refresh=${if (forceRefresh) 1 else 0}",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Regex("\"token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: error("backend returned no token")
        } finally {
            connection.disconnect()
        }
    }
}
