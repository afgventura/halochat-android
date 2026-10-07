package id.co.haloai.halochat.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import id.co.haloai.halochat.HaloChatMessage
import kotlinx.coroutines.launch
import java.io.File

/**
 * The chat screen. Open it with `HaloChatWidget.instance.initiateChat()…startChat(context)`.
 * Declared in the SDK's manifest with the SDK's own AppCompat theme, so it works in any host app.
 */
public class HaloChatActivity : AppCompatActivity(), ChatActions {
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var options: ChatOptions
    private lateinit var color: HaloChatColor

    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var banner: TextView
    private lateinit var list: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: ChatAdapter
    private lateinit var empty: TextView
    private lateinit var loading: ProgressBar
    private lateinit var olderLoading: ProgressBar
    private lateinit var composer: LinearLayout
    private lateinit var input: EditText
    private lateinit var send: ImageButton
    private lateinit var attach: ImageButton
    private lateinit var expired: TextView

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = viewModel.pendingCameraUri
        viewModel.pendingCameraUri = null
        if (saved && uri != null) {
            val parsed = Uri.parse(uri)
            viewModel.attach(contentResolver, parsed, parsed.lastPathSegment, "image/jpeg")
        }
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast(R.string.halochat_error_camera_permission)
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attach(contentResolver, uri)
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(contentResolver, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        options = ChatOptions.from(intent.extras)
        // Normally set up by the host's Application; a default instance keeps a cold start safe.
        val widget = HaloChatWidget.setup(this)
        color = widget.color
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildContent())
        styleSystemBars()

        adapter = ChatAdapter(this, color, widget.media, lifecycleScope, this)
        adapter.avatarEnabled = options.avatar == Avatar.ENABLED
        list.adapter = adapter

        viewModel.start(options)
        // Once per chat: after rotation the EditText restores whatever the user left in it.
        viewModel.consumeManualMessage()?.let { text ->
            input.setText(text)
            input.setSelection(input.length())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.runTimeline() }
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.notices.collect { notice ->
                        when (notice) {
                            is ChatViewModel.Notice.Attachment -> toast(
                                if (notice.problem == AttachmentProblem.TOO_LARGE) R.string.halochat_error_too_large
                                else R.string.halochat_error_unsupported_type,
                            )
                            ChatViewModel.Notice.ReadFailed -> toast(R.string.halochat_error_read_failed)
                            ChatViewModel.Notice.CannotOpen -> toast(R.string.halochat_error_no_app)
                            ChatViewModel.Notice.DownloadFailed -> toast(R.string.halochat_error_download)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ChatScreenTracker.onResume()
        clearChatNotifications()
    }

    override fun onPause() {
        ChatScreenTracker.onPause()
        super.onPause()
    }

    private var lastRows: List<ChatRow> = emptyList()

    private fun render(state: ChatUiState) {
        titleView.text = state.title ?: ""
        adapter.senderName = state.title ?: getString(R.string.halochat_subtitle_default)
        banner.visibility = if (state.banner != null && !state.sessionExpired) View.VISIBLE else View.GONE
        banner.setText(
            if (state.banner == Banner.RATE_LIMITED) R.string.halochat_banner_rate_limited else R.string.halochat_banner_reconnecting,
        )
        loading.visibility = if (!state.loaded && state.rows.isEmpty()) View.VISIBLE else View.GONE
        empty.visibility = if (state.loaded && state.rows.isEmpty() && !state.sessionExpired) View.VISIBLE else View.GONE
        olderLoading.visibility = if (state.loadingOlder) View.VISIBLE else View.GONE
        composer.visibility = if (state.sessionExpired) View.GONE else View.VISIBLE
        expired.visibility = if (state.sessionExpired) View.VISIBLE else View.GONE

        if (state.rows === lastRows) return
        val previous = lastRows
        lastRows = state.rows
        val nearBottom = previous.isEmpty() || layoutManager.findLastVisibleItemPosition() >= previous.size - 3
        val newestKey = state.rows.lastOrNull()?.key
        val userJustSent = newestKey != previous.lastOrNull()?.key && state.rows.lastOrNull() is ChatRow.Pending
        adapter.submitList(state.rows) {
            if ((nearBottom || userJustSent) && state.rows.isNotEmpty() && newestKey != previous.lastOrNull()?.key) {
                list.scrollToPosition(state.rows.size - 1)
            }
            maybeLoadOlder()
        }
    }

    /** Scrolled to (or cannot scroll away from) the top: fetch the next older page. */
    private fun maybeLoadOlder() {
        if (!viewModel.canLoadOlder || adapter.itemCount == 0) return
        if (layoutManager.findFirstVisibleItemPosition() <= 2 || !list.canScrollVertically(-1)) viewModel.loadOlder()
    }

    private fun sendTyped() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        viewModel.send(text)
        input.setText("")
    }

    private fun showAttachSheet() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            items += getString(R.string.halochat_attach_camera) to ::startCamera
        }
        items += getString(R.string.halochat_attach_gallery) to {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        items += getString(R.string.halochat_attach_document) to {
            openDocument.launch(AttachmentRules.PICKER_MIME_TYPES)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.halochat_attach_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** If the HOST app declares CAMERA, the system requires it granted before ACTION_IMAGE_CAPTURE. */
    private fun startCamera() {
        if (hostDeclaresCamera() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else {
            launchCamera()
        }
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "halochat/camera").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, fileProviderAuthority(this), file)
        viewModel.pendingCameraUri = uri.toString()
        try {
            takePicture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            viewModel.pendingCameraUri = null
            toast(R.string.halochat_error_no_app)
        }
    }

    @Suppress("DEPRECATION")
    private fun hostDeclaresCamera(): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)

    override fun retry(clientMessageId: String) {
        viewModel.retry(clientMessageId)
    }

    override fun openImage(message: HaloChatMessage) {
        startActivity(HaloChatImageActivity.intent(this, message.id))
    }

    override fun openFile(message: HaloChatMessage) {
        val widget = HaloChatWidget.instanceOrNull ?: return
        toast(R.string.halochat_opening_file)
        lifecycleScope.launch {
            val file = try {
                widget.media.file(message.id, message.mediaFilename)
            } catch (_: Exception) {
                viewModel.notice(ChatViewModel.Notice.DownloadFailed)
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@HaloChatActivity, fileProviderAuthority(this@HaloChatActivity), file)
            val mime = message.mediaType
                ?: AttachmentRules.resolveMime(null, file.name) { ext ->
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                }
                ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            @Suppress("DEPRECATION")
            val handlers = packageManager.queryIntentActivities(view, 0)
            if (handlers.isEmpty()) {
                viewModel.notice(ChatViewModel.Notice.CannotOpen)
                return@launch
            }
            try {
                startActivity(Intent.createChooser(view, getString(R.string.halochat_open_with)))
            } catch (_: ActivityNotFoundException) {
                viewModel.notice(ChatViewModel.Notice.CannotOpen)
            }
        }
    }

    private fun clearChatNotifications() {
        if (Build.VERSION.SDK_INT < 23) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.filter { it.tag == NOTIFICATION_TAG }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun styleSystemBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = color.navigationColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = color.sendContainerBackgroundColor
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLight(color.navigationColor)
        if (Build.VERSION.SDK_INT >= 26) controller.isAppearanceLightNavigationBars = isLight(color.sendContainerBackgroundColor)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color.baseColor)
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color.navigationColor)
            minimumHeight = dp(56)
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.halochat_ic_back)
            tint(color.navigationTitleColor)
            background = null
            contentDescription = getString(R.string.halochat_back)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(16), dp(6))
        }
        titleView = TextView(this).apply {
            setTextColor(color.navigationTitleColor)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        subtitleView = TextView(this).apply {
            setTextColor(color.navigationTitleColor)
            alpha = 0.85f
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        when (options.subtitle) {
            RoomSubtitle.ENABLED -> subtitleView.setText(R.string.halochat_subtitle_default)
            RoomSubtitle.EDITABLE -> subtitleView.text = options.subtitleText.orEmpty()
            RoomSubtitle.DISABLED -> Unit
        }
        subtitleView.visibility =
            if (options.subtitle == RoomSubtitle.DISABLED || subtitleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        titles.addView(titleView)
        titles.addView(subtitleView)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        banner = TextView(this).apply {
            setBackgroundColor(0xFFFFF4CE.toInt())
            setTextColor(0xFF5C4400.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = FrameLayout(this)
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list = RecyclerView(this).apply {
            this.layoutManager = this@HaloChatActivity.layoutManager
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy < 0) maybeLoadOlder()
                }
            })
        }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = TextView(this).apply {
            setText(R.string.halochat_empty)
            setTextColor(color.emptyTextColor)
            setBackgroundColor(color.emptyBackgroundColor)
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loading = ProgressBar(this).apply { isIndeterminate = true }
        body.addView(loading, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        olderLoading = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        body.addView(olderLoading, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(8)
        })
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(color.sendContainerBackgroundColor)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        attach = ImageButton(this).apply {
            setImageResource(R.drawable.halochat_ic_attach)
            tint(color.sendContainerColor)
            background = null
            contentDescription = getString(R.string.halochat_attach)
            setOnClickListener { showAttachSheet() }
        }
        composer.addView(attach, LinearLayout.LayoutParams(dp(44), dp(44)))
        input = EditText(this).apply {
            hint = getString(R.string.halochat_composer_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            minHeight = dp(44)
            textSize = 15f
            filters = arrayOf(InputFilter.LengthFilter(MAX_TEXT))
            background = rounded(color.sendContainerBackgroundColor, dp(22).toFloat(), dp(1), color.fieldChatBorderColor)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        send = ImageButton(this).apply {
            setImageResource(R.drawable.halochat_ic_send)
            tint(color.sendContainerColor)
            background = null
            contentDescription = getString(R.string.halochat_send)
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { sendTyped() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val enabled = !s?.toString()?.trim().isNullOrEmpty()
                send.isEnabled = enabled
                send.alpha = if (enabled) 1f else 0.4f
            }
        })
        root.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        expired = TextView(this).apply {
            setText(R.string.halochat_session_expired)
            setTextColor(0xFFB3261E.toInt())
            setBackgroundColor(color.sendContainerBackgroundColor)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        root.addView(expired, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(0, bars.top, 0, 0)
            view.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    public companion object {
        private const val MAX_TEXT = 4096
        internal const val NOTIFICATION_TAG = "halochat"

        internal fun intent(context: Context, options: ChatOptions): Intent {
            val intent = options.writeTo(Intent(context, HaloChatActivity::class.java))
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }
    }
}

/** `${applicationId}.halochat.fileprovider`, declared by the SDK's manifest. */
internal fun fileProviderAuthority(context: Context): String = context.packageName + ".halochat.fileprovider"

/** The SDK's own FileProvider subclass, so its manifest entry never merges with the host app's. */
internal class HaloChatFileProvider : FileProvider()
