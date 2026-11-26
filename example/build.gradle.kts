plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    implementation("com.github.afgventura.halochat-android:halochat:0.2.1")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("SampleKt") }
