// corvid-android — the AAR assembly build (docs/PLAN.md "Android").
//
// A SEPARATE Gradle build from the root JVM project on purpose: the root
// stays pure-JVM (no Android SDK requirement on any desktop dev machine
// or CI leg); this build compiles the SAME Kotlin wrapper sources
// (srcDir ../src/main/kotlin — no fork, no copy) against android.jar and
// packages them, with the jniLibs pairs staged by
// scripts/build-native-android.sh, as io.github.corvid-db:corvid-android.
//
// Run through the repo-root wrapper: ./gradlew -p android <task>
// (assembleRelease | test | connectedDebugAndroidTest | publish…Staging).
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

rootProject.name = "corvid-android"
