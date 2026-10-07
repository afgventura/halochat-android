# HaloChat UI sample app (Android)

A minimal app that opens the drop-in chat screen. It builds against the SDK sources next to it
(Gradle composite build: `includeBuild("..")`), so it always matches this checkout.

1. Start the stand-in backend with your channel's server key (it listens on the host's
   `127.0.0.1:8791`, which the emulator reaches as `10.0.2.2:8791`):
   `HALOCHAT_SERVER_KEY=hck_… node ../../halochat-ios/Example/backend/server.mjs`
   (in the public repos: the `Example/backend/server.mjs` of `halochat-ios`)
2. Build and install: `./gradlew -p example-app installDebug` (from `halochat-android/`;
   needs the Android SDK, e.g. `ANDROID_HOME` or `example-app/local.properties`).
   On a real device pass your machine's address: `-PtokenBackend=http://192.168.1.10:8791`
   (and allow it in `src/main/res/xml/network_security_config.xml`).
3. Tap **Sign in**, then **Open chat**.

`SampleApp` sets the widget up; `MainActivity` shows `setUser`, `initiateChat()…startChat`,
`clearUser` and `tapNotification`. Push needs your own Firebase project; see the main README.
