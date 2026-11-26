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
