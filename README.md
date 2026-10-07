# HaloChat for Android

Put your signed-in users in a chat with your team (HaloAI's AI agent + your human agents) from
inside your own Android app. Two artifacts:

- **`halochat-ui`**: a drop-in chat screen (`HaloChatActivity`) with attachments, retries,
  notifications and theming. Its API mirrors the Qiscus Multichannel Widget, so a migration is
  mostly a rename.
- **`halochat`**: the core SDK the screen is built on (networking, auth, realtime, push,
  attachments). Use it alone if you want your own UI (see "Advanced" below).

You need a HaloAI account with a HaloChat channel; your HaloAI team provides it together with
the integration guide.

## Install

minSdk 21, compileSdk 35+. Android Views (AppCompat + RecyclerView): works in View and Compose
apps. No Glide, Coil, Material Components or Firebase dependency. The SDK declares `INTERNET`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.afgventura.halochat-android:halochat-ui:0.2.1")
}
```

JitPack serves this repository as a multi-module build: one module is
`com.github.afgventura.halochat-android:<artifactId>:<tag>`. `halochat-ui` pulls in the core;
core only (no UI) is `com.github.afgventura.halochat-android:halochat:0.2.1`.
(`com.github.afgventura:halochat-android:0.2.1` would fetch every module.)

## Quick start

### 1. Your backend mints the token

Never put the channel's HaloChat server key (`hck_…`) in the app. After your own login
succeeds, your server calls `POST /api/open/inApp/v1/clientToken` with that key and returns
the `hct_…` token to the app.

### 2. Set the widget up once

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HaloChatWidget.setup(
            this,
            HaloChatWidgetConfig().setNotificationIcon(R.drawable.ic_notification),
            HaloChatColor().setNavigationColor(0xFF1F4E8C.toInt()),
        )
    }
}
```

### 3. Set the user after your login

```kotlin
HaloChatWidget.instance.setUser { forceRefresh ->
    // forceRefresh == true means HaloAI refused the last token: mint a new one.
    myApi.haloChatToken(forceRefresh)     // suspend; runs off the main thread in your code
}
```

From Java: `HaloChatWidget.getInstance().setUserBlocking(forceRefresh -> myApi.haloChatTokenBlocking(forceRefresh));`
(called on a background thread). If your app restores a session at launch, call `setUser` in
`Application.onCreate` too, so a notification tap after process death finds a signed-in user.

### 4. Open the chat

```kotlin
HaloChatWidget.instance.initiateChat()
    .setRoomTitle("Bantuan")                                  // default: the channel label
    .setRoomSubtitle(RoomSubtitle.EDITABLE, "Biasanya membalas dalam beberapa menit")
    .setShowSystemMessage(false)
    .setAvatar(Avatar.ENABLED)
    .automaticSendMessage("Halo, saya butuh bantuan")         // sent once when the chat opens
    // .manualSendMessage("Pesanan #123: ")                   // or: pre-filled in the composer
    .startChat(context)
```

### 5. Sign out

```kotlin
lifecycleScope.launch { HaloChatWidget.instance.clearUser() }  // or clearUser(Runnable { … }) / clearUser(null)
```

It stops this device's pushes, expires the HaloChat token on the server and clears cached media.

## What the chat screen does

- Header with back, title and subtitle; empty state; a non-blocking "Reconnecting…" banner while
  offline or rate limited; "Session expired" (and no composer) when your token provider can no
  longer mint.
- Live timeline (backfill, realtime socket, polling fallback) only while the screen is visible;
  older history loads as the user scrolls up. Date separators (Today / Yesterday / date), time
  and status ticks (sending, failed, sent ✓, read ✓✓), system events as centered pills.
- Composer (multi-line, 4096 characters) and an attach sheet: **Camera**, **Photo or video**,
  **Document**. Files are checked before upload (25 MB; JPEG, PNG, WebP, GIF, HEIC, MP4, MOV,
  MP3, M4A, AAC, OGG, PDF, DOCX, XLSX, TXT) with a localized reason when rejected.
- Every send shows immediately; a failed one says why and retries on tap with the same
  idempotency key and the same bytes, so a retry can never create a duplicate.
- Images render as thumbnails (tap for full screen); other files as cards that download and open
  in the user's apps through the SDK's own `FileProvider`.
- English and Indonesian, following the device language. Rotation keeps the state.

### Host app manifest

Nothing to add. The SDK merges its own activities, a `FileProvider` with authority
`${applicationId}.halochat.fileprovider` (its own class, so it never clashes with yours), and
`<queries>` for opening files and the camera.

**Camera permission:** the SDK does not declare `CAMERA` (the system camera app takes the photo).
But if **your** app declares `android.permission.CAMERA`, Android refuses the capture unless it is
granted, so the SDK requests it at runtime before opening the camera. Nothing to do on your side.

## Customization

### Config (`HaloChatWidgetConfig`, set once in `setup`)

| Setter | Default | Meaning |
|---|---|---|
| `setEnableNotification(Boolean)` | `true` | `handleNotification` posts notifications |
| `setNotificationIcon(@DrawableRes Int)` | SDK chat icon | status-bar small icon (white on transparent) |
| `setNotificationChannelName(String)` | `"HaloChat"` | channel name in system settings (channel id `halochat_messages`) |

### Per chat (`initiateChat()` builder)

| Builder | Default | Meaning |
|---|---|---|
| `setRoomTitle(String)` | channel label | header title |
| `setRoomSubtitle(RoomSubtitle, String?)` | `ENABLED` | `ENABLED` = localized "Customer Service", `DISABLED` = none, `EDITABLE` = your text |
| `setShowSystemMessage(Boolean)` | `true` | show system events (`senderKind == system`) |
| `setAvatar(Avatar)` | `ENABLED` | initial avatar + name on the first business bubble of each run |
| `automaticSendMessage(String)` | none | sent once, as the user, when the chat opens |
| `manualSendMessage(String)` | none | pre-filled in the composer |

### Colors (`HaloChatColor`, the same 15 names as Qiscus)

Left = the business side (AI / human / automation), right = your user. All take a `@ColorInt`.

| Setter | Colors |
|---|---|
| `setNavigationColor` | header background (and status bar) |
| `setNavigationTitleColor` | title, subtitle, back button |
| `setSendContainerColor` | send and attach icons |
| `setSendContainerBackgroundColor` | composer bar background (and navigation bar) |
| `setFieldChatBorderColor` | text field border |
| `setSystemEventTextColor` | system event pill text and border |
| `setLeftBubbleColor` | business bubble |
| `setRightBubbleColor` | user bubble |
| `setLeftBubbleTextColor` | business bubble text |
| `setRightBubbleTextColor` | user bubble text |
| `setTimeLabelTextColor` | times, date separators, sender names |
| `setTimeBackgroundColor` | date separator pill, image placeholder |
| `setBaseColor` | chat background |
| `setEmptyTextColor` | empty state text |
| `setEmptyBackgroundColor` | empty state background |

## Push notifications

The SDK adds no Firebase dependency; you keep your own `FirebaseMessagingService`. HaloAI sends
pushes from **your** Firebase project; share its service-account key once through the HaloAI
team (`channel_in_app_set_push_credentials`).

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        HaloChatWidget.instance.registerDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val widget = HaloChatWidget.instance
        if (widget.isHaloChatNotification(message.data)) {
            widget.handleNotification(this, message.data, message.notification?.title, message.notification?.body)
            return
        }
        // your other pushes
    }
}

// After every sign-in, too (the SDK re-registers by itself whenever it re-mints a token):
HaloChatWidget.instance.registerDeviceToken(FirebaseMessaging.getInstance().token.await())

// Launcher activity: the app was opened from a system-displayed (background) HaloChat push.
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    HaloChatWidget.instance.tapNotification(this, intent)
}
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    HaloChatWidget.instance.tapNotification(this, intent)
}
```

- `handleNotification` posts on the SDK's channel; tapping opens the chat. It returns `true`
  when it handled the push, including when it deliberately shows nothing because the chat is on
  screen (the timeline already shows the message).
- On Android 13+ it needs `POST_NOTIFICATIONS`, which **your app** declares and requests. Without
  the grant it posts nothing and returns `false`.
- FCM's `notification` payload is shown by the system while your app is in the background; the
  tap launches your launcher activity with the data as extras, which `tapNotification` detects
  (`type == "halochat_message"`).

## Coming from Qiscus Multichannel Widget

| Qiscus Multichannel Widget | HaloChat UI |
|---|---|
| `QiscusMultichannelWidget.setup(app, core, appId, config, color, prefKey)` | `HaloChatWidget.setup(context, config, color)` |
| `QiscusMultichannelWidget.instance` | `HaloChatWidget.instance` |
| `setUser(userId, displayName, avatarUrl, props)` | `setUser(tokenProvider)`: your backend mints the `hct_` token (user id, display name and phone go in the mint) |
| `isLoggedIn()` / `hasSetupUser()` | `isLoggedIn()` |
| `initiateChat()…startChat(context)` | `initiateChat()…startChat(context)` |
| `setRoomTitle` | `setRoomTitle` |
| `setRoomSubtitle(RoomSubtitle.Enabled / Disabled / Editable, text)` | `setRoomSubtitle(RoomSubtitle.ENABLED / DISABLED / EDITABLE, text)` |
| `setShowSystemMessage(Boolean)` / `setHideUIEvent` | `setShowSystemMessage(Boolean)` |
| `setAvatar(Avatar.Enable / Disabled)` | `setAvatar(Avatar.ENABLED / DISABLED)` |
| `automaticSendMessage(text)` / `manualSendMessage(text)` | same |
| `onCompleted(SessionCompleteListener)` | not needed: register the push token after `setUser` |
| `QiscusMultichannelWidgetConfig().setEnableNotification(…).setNotificationIcon(…)` | `HaloChatWidgetConfig().setEnableNotification(…).setNotificationIcon(…)` |
| `setNotificationListener(…)` | `isHaloChatNotification(data)` + `handleNotification(context, data, title, body)` |
| `isMultichannelMessage(remoteMessage, cores)` | `isHaloChatNotification(remoteMessage.data)` |
| `registerDeviceToken(core, token)` | `registerDeviceToken(token)` |
| `clearUser()` | `clearUser()` |
| `QiscusMultichannelWidgetColor` (15 setters) | `HaloChatColor` (the same 15 setters) |
| Manifest `FileProvider` + `qiscus_provider_paths` | nothing: the SDK ships its own provider |
| Proguard rules file | nothing: consumer rules ship in the AAR |

`HaloChatWidget.instance.client` is the core `HaloChatClient` of the signed-in user, for anything
the screen does not cover.

## Advanced: core SDK without UI

`com.github.afgventura.halochat-android:halochat:0.2.1` is a plain Kotlin/JVM library (Java 8
bytecode; no `java.time`, so minSdk 21+ works without desugaring) built on OkHttp 4, kotlinx
coroutines and kotlinx.serialization. The app needs the `INTERNET` permission.

### Create the client

```kotlin
val chat = HaloChatClient(
    tokenProvider = { forceRefresh ->
        // forceRefresh == true means HaloAI refused the last token: mint a new one.
        myApi.haloChatToken(forceRefresh)
    },
)
```

### Show the conversation

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {       // foreground only
        chat.timeline(onError = { error ->
            // Offline / rate limited: the flow keeps retrying by itself.
            // HaloChatException.Unauthorized: your token provider can no longer mint; sign in again.
            showBanner(error)
        }).collect { messages -> adapter.submitList(messages) }  // ascending, de-duplicated
    }
}
```

`message.sender` is `ME` (your user) or `AGENT` (AI / human / automation; see
`senderKind`). Older pages: `chat.messagesBefore(oldestCursor)`; keep them in your own
list and merge with the timeline by id (the flow carries the live window only).

### Send

```kotlin
val id = UUID.randomUUID().toString()   // keep it until the send succeeds
chat.send("Halo", clientMessageId = id)
// Timed out? Retry with the SAME id: it can never create a second message.

chat.sendAttachment(bytes, "ktp.jpg", "image/jpeg", caption = "KTP saya")
val url = chat.mediaUrl(message.id)     // short-lived download URL
```

### Push (background)

```kotlin
// After every sign-in:
chat.registerPushToken(FirebaseMessaging.getInstance().token.await())

class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { scope.launch { chat.registerPushToken(token) } }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "halochat_message") showNotification(message)
    }
}
```

The SDK remembers the token and re-registers it by itself whenever it re-mints your
HaloChat token, so pushes keep flowing.

### Sign out

```kotlin
chat.signOut()   // stops this device's pushes and expires its HaloChat token
```

HaloAI sends pushes from **your** Firebase project; share its service-account key
once through the HaloAI team (`channel_in_app_set_push_credentials`).

### Errors

`HaloChatException`: `Unauthorized` (token refused after one refresh), `RateLimited(retryAfterSeconds)`,
`SendInProgress` (same id still running; re-read then retry), `Rejected(status, code)`, `Server(status)`,
`InvalidResponse`. Exceptions thrown by your token provider are passed through unchanged.

### Coming from Qiscus core SDK (`qiscus-sdk-android`)

| Qiscus | HaloChat |
|---|---|
| `QiscusCore.initWithAppId` | `HaloChatClient(config, tokenProvider)` |
| `QiscusCore.setUser(userId, userKey)` | backend-minted `hct_` token via `tokenProvider` |
| `chatUser` / `getAllChatRooms` | `conversation()` (one CS room per user) |
| `sendMessage` / file upload | `send(...)`, `sendAttachment(...)` |
| EventBus `QiscusCommentReceivedEvent` | `timeline()` `Flow` |
| `QiscusCore.registerDeviceToken` | `registerPushToken(...)` |
| `QiscusCore.clearUser` | `signOut()` + drop the client |

## Develop

Core tests: `./gradlew test` (JDK 17). The UI AAR needs the Android SDK:
`./gradlew :ui:assembleRelease`. Without a local JDK / SDK:

```bash
docker run --rm --platform linux/amd64 -v "$PWD":/work -w /work ghcr.io/cirruslabs/android-sdk:35 \
  bash -c 'apt-get update -qq && apt-get install -y -qq openjdk-17-jdk-headless >/dev/null && \
    ./gradlew --no-daemon test :ui:assembleRelease publishToMavenLocal'
```

## Samples

- `example-app/`: an Android app with the chat screen, built against these sources
  (`./gradlew -p example-app assembleDebug`). See `example-app/README.md`.
- `example/`: a console sample of the core SDK on the published JitPack artifact. See
  `example/README.md`.

## License

Copyright 2026 HaloAI. Licensed under the [Apache License 2.0](LICENSE): free to use,
modify and ship in your own (including closed-source) app. The SDK holds no secrets;
it only talks to the HaloAI service your business is subscribed to.
