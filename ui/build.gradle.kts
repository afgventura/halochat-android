plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

android {
    namespace = "id.co.haloai.halochat.ui"
    compileSdk = 35
    resourcePrefix = "halochat_"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    api(rootProject)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("halochatUi") {
                artifactId = "halochat-ui"
                from(components["release"])
                pom {
                    name.set("HaloChat UI for Android")
                    description.set("Drop-in customer chat screen for the HaloChat SDK.")
                    url.set("https://github.com/afgventura/halochat-android")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }
                }
            }
        }
    }
}
