# HaloChat for Android

Put your signed-in users in a chat with your team (HaloAI's AI agent + your human
agents) from inside your own Android app. Core SDK only: networking, auth, realtime,
push, attachments. Your app owns the UI.

You need a HaloAI account with a HaloChat channel; your HaloAI team provides it together with
the integration guide.

## Install

A plain Kotlin/JVM library (Java 8 bytecode; no `java.time`, so minSdk 21+ works without
desugaring) built on OkHttp 4, kotlinx coroutines and kotlinx.serialization. The app needs the
`INTERNET` permission.

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
    implementation("com.github.afgventura:halochat-android:0.1.0")
}
```

## 1. Your backend mints the token

Never put the channel's HaloChat server key (`hck_…`) in the app. After your own login
succeeds, your server calls `POST /api/open/inApp/v1/clientToken` with that key and returns
the `hct_…` token.

## 2. Create the client

```kotlin
val chat = HaloChatClient(
    tokenProvider = { forceRefresh ->
        // forceRefresh == true means HaloAI refused the last token: mint a new one.
        myApi.haloChatToken(forceRefresh)
    },
)
```

## 3. Show the conversation

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

## 4. Send

```kotlin
val id = UUID.randomUUID().toString()   // keep it until the send succeeds
chat.send("Halo", clientMessageId = id)
// Timed out? Retry with the SAME id: it can never create a second message.

chat.sendAttachment(bytes, "ktp.jpg", "image/jpeg", caption = "KTP saya")
val url = chat.mediaUrl(message.id)     // short-lived download URL
```

## 5. Push (background)

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

## 6. Sign out

```kotlin
chat.signOut()   // stops this device's pushes and expires its HaloChat token
```

HaloAI sends pushes from **your** Firebase project; share its service-account key
once through the HaloAI team (`channel_in_app_set_push_credentials`).

## Errors

`HaloChatException`: `Unauthorized` (token refused after one refresh), `RateLimited(retryAfterSeconds)`,
`SendInProgress` (same id still running; re-read then retry), `Rejected(status, code)`, `Server(status)`,
`InvalidResponse`. Exceptions thrown by your token provider are passed through unchanged.

## Coming from Qiscus

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

`./gradlew test` (JDK 17). Without a local JDK:
`docker run --rm -v "$PWD":/work -w /work gradle:8.10.2-jdk17 ./gradlew test`.

## Sample

`example/` is a console sample on the published JitPack artifact (the SDK is plain
Kotlin/JVM, so it runs the same code as an Android app). See `example/README.md`.

## License

Copyright 2026 HaloAI. Licensed under the [Apache License 2.0](LICENSE): free to use,
modify and ship in your own (including closed-source) app. The SDK holds no secrets;
it only talks to the HaloAI service your business is subscribed to.
