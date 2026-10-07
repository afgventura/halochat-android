// A separate Android app build on the LOCAL SDK sources (composite build), so it always
// matches the code next to it. Apps install the published artifacts instead (see README).
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

rootProject.name = "halochat-example-app"

includeBuild("..") {
    dependencySubstitution {
        substitute(module("id.co.haloai:halochat")).using(project(":"))
        substitute(module("id.co.haloai:halochat-ui")).using(project(":ui"))
    }
}
