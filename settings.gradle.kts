pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "halochat"

// The drop-in chat screen (Android library, artifactId `halochat-ui`). The root project is the
// core SDK (plain Kotlin/JVM, artifactId `halochat`).
include(":ui")
