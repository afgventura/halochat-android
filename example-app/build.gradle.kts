plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
}

android {
    namespace = "id.co.haloai.halochat.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.co.haloai.halochat.example"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.1"
        // The stand-in backend (../../halochat-ios/Example/backend/server.mjs) on the host
        // machine, as seen from the Android emulator.
        buildConfigField("String", "TOKEN_BACKEND", "\"${project.findProperty("tokenBackend") ?: "http://10.0.2.2:8791"}\"")
        // HaloChat API base; defaults to production. `-PhaloBaseUrl=http://10.0.2.2:8791` targets a local server.
        buildConfigField("String", "HALO_BASE_URL", "\"${project.findProperty("haloBaseUrl") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Resolved to ../ui by the composite build in settings.gradle.kts.
    implementation("id.co.haloai:halochat-ui:0.2.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
