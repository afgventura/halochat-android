// A separate build: the SDK's own build (and JitPack) never sees this sample.
rootProject.name = "halochat-sample"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
