plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    // The published artifact, exactly as an app installs it.
    implementation("com.github.afgventura:halochat-android:0.1.0")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("SampleKt") }
