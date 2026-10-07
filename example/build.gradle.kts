plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    // The published artifact, exactly as an app installs it.
    // JitPack serves this multi-module repo as com.github.afgventura.halochat-android:<artifactId>.
    implementation("com.github.afgventura.halochat-android:halochat:0.2.1")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("SampleKt") }
