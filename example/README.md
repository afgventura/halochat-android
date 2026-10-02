# HaloChat Android/JVM sample

The SDK is plain Kotlin/JVM (no Android framework APIs), so this console sample runs the
same code your Android app will. It installs the **published** artifact from JitPack.

1. Start the stand-in backend with your channel's server key:
   `HALOCHAT_SERVER_KEY=hck_… node ../../halochat-ios/Example/backend/server.mjs`
   (in the public repos: the `Example/backend/server.mjs` of `halochat-ios`)
2. `./gradlew -p example run --args="sample-user-1 Halo dari Android"`

It opens the conversation, sends a message, waits for the AI/agent reply on the live
timeline, and signs out.
