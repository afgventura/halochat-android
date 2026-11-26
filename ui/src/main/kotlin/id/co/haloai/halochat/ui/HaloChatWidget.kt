package id.co.haloai.halochat.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.co.haloai.halochat.HaloChatClient
import id.co.haloai.halochat.HaloChatConfig
import id.co.haloai.halochat.HaloChatTokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

public class HaloChatWidget private constructor(
    context: Context,
    public val config: HaloChatWidgetConfig,
    public val color: HaloChatColor,
) {
    internal val appContext: Context = context.applicationContext

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val media: MediaRepository = MediaRepository(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentClient: HaloChatClient? = null

    @Volatile
    private var deviceToken: String? = null

    @Volatile
    internal var lastOptions: ChatOptions = ChatOptions()

    public val client: HaloChatClient? get() = currentClient

    public fun setUser(tokenProvider: HaloChatTokenProvider) {
        setUser(tokenProvider, HaloChatConfig())
    }

    public fun setUser(coreConfig: HaloChatConfig, tokenProvider: HaloChatTokenProvider) {
        setUser(tokenProvider, coreConfig)
    }

    public fun setUser(tokenProvider: HaloChatTokenProvider, coreConfig: HaloChatConfig) {
        val client = HaloChatClient(config = coreConfig, tokenProvider = tokenProvider, httpClient = http)
        currentClient = client
        media.clear()
        deviceToken?.let { token -> scope.launch { runCatching { client.registerPushToken(token) } } }
    }

    public fun setUserBlocking(tokenProvider: HaloChatBlockingTokenProvider) {
        setUserBlocking(tokenProvider, HaloChatConfig())
    }

    public fun setUserBlocking(tokenProvider: HaloChatBlockingTokenProvider, coreConfig: HaloChatConfig) {
        setUser({ forceRefresh -> withContext(Dispatchers.IO) { tokenProvider.token(forceRefresh) } }, coreConfig)
    }

    public fun isLoggedIn(): Boolean = currentClient != null

    public suspend fun clearUser() {
        val client = currentClient
        currentClient = null
        media.clear()
        if (client != null) runCatching { client.signOut() }
    }

    public fun clearUser(onDone: Runnable?) {
        scope.launch {
            clearUser()
            onDone?.let { mainHandler.post(it) }
        }
    }

    public fun registerDeviceToken(token: String) {
        deviceToken = token
        val client = currentClient ?: return
        scope.launch { runCatching { client.registerPushToken(token) } }
    }

    public fun initiateChat(): ChatBuilder = ChatBuilder(this)

    public fun isHaloChatNotification(data: Map<String, String>): Boolean = data[KEY_TYPE] == TYPE_MESSAGE

    @JvmOverloads
    public fun handleNotification(
        context: Context,
        data: Map<String, String>,
        title: String?,
        body: String?,
        smallIcon: Int = config.notificationIcon,
    ): Boolean {
        if (!isHaloChatNotification(data) || !config.enableNotification) return false
        if (ChatScreenTracker.isResumed) return true
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(app)
        val roomId = data["roomId"]
        val messageId = data["messageId"]
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = HaloChatActivity.intent(app, lastOptions).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val tap = PendingIntent.getActivity(app, REQUEST_OPEN_CHAT, open, flags)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(if (smallIcon != 0) smallIcon else R.drawable.halochat_ic_notification)
            .setContentTitle(title ?: app.getString(R.string.halochat_notification_default_title))
            .setContentText(body.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.orEmpty()))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .apply { if (roomId != null) setGroup("halochat:$roomId") }
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_TAG, (messageId ?: body ?: "").hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    public fun tapNotification(context: Context, intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        if (extras.getString(KEY_TYPE) != TYPE_MESSAGE) return false
        intent.removeExtra(KEY_TYPE)
        context.startActivity(HaloChatActivity.intent(context, lastOptions))
        return true
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = config.notificationChannelName ?: context.getString(R.string.halochat_notification_channel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH))
    }

    public companion object {
        public const val CHANNEL_ID: String = "halochat_messages"

        private const val KEY_TYPE = "type"
        private const val TYPE_MESSAGE = "halochat_message"
        private const val NOTIFICATION_TAG = "halochat"
        private const val REQUEST_OPEN_CHAT = 0x4a10

        @Volatile
        private var shared: HaloChatWidget? = null

        @JvmStatic
        @JvmOverloads
        public fun setup(
            context: Context,
            config: HaloChatWidgetConfig = HaloChatWidgetConfig(),
            color: HaloChatColor = HaloChatColor(),
        ): HaloChatWidget = shared ?: synchronized(this) {
            shared ?: HaloChatWidget(context, config, color).also { shared = it }
        }

        @JvmStatic
        public val instance: HaloChatWidget
            get() = shared ?: error("HaloChatWidget.setup(context) has not been called")

        internal val instanceOrNull: HaloChatWidget? get() = shared
    }
}

public fun interface HaloChatBlockingTokenProvider {
    public fun token(forceRefresh: Boolean): String
}

internal object ChatScreenTracker {
    @Volatile
    private var resumed = 0

    val isResumed: Boolean get() = resumed > 0

    @Synchronized
    fun onResume() {
        resumed++
    }

    @Synchronized
    fun onPause() {
        resumed = (resumed - 1).coerceAtLeast(0)
    }
}
