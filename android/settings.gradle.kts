pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://repo1.maven.org/maven2/") }
        // Legacy jcenter mirror (read-only) for old artifacts like org.webrtc:google-webrtc
        maven { url = uri("https://jcenter.bintray.com/") }
    }
}

rootProject.name = "Securicam"
include(":app")
