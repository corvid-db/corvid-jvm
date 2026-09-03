// corvid-android — the AAR (io.github.corvid-db:corvid-android).
//
// Artifact shape: ONE AAR carrying the full Kotlin wrapper (the same
// sources as the JVM jar) plus BOTH ABI pairs as jniLibs —
//   jniLibs/arm64-v8a/{libcorvid.so,libcorvidjni.so}
//   jniLibs/x86_64/{libcorvid.so,libcorvidjni.so}
// (staged by scripts/build-native-android.sh from the pinned engine
// release + the NDK shim build). Consumers need nothing else: the
// package manager installs the .so files into the app's
// nativeLibraryDir and Corvid.load()'s Android branch resolves them via
// System.loadLibrary (NativeLoading.onAndroid).
//
// minSdk 26: the shared NativeLoading uses java.nio.file (API 26+);
// the engine .so files themselves target API 24 — a superset.
plugins {
    id("com.android.library") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    `maven-publish`
    signing
}

// Same derivation as the root build: the engine pin (fetch.sh's
// CORVID_VERSION) owns the version; the release workflow passes
// -Pversion=<tag-without-v> and additionally verifies tag == pin there.
val enginePin: String = providers.fileContents(layout.projectDirectory.file("../fetch.sh"))
    .asText.get()
    .lineSequence()
    .first { it.startsWith("CORVID_VERSION=") }
    .substringAfter('"').substringBefore('"')

group = "io.github.corvid-db"
version = providers.gradleProperty("version")
    .orElse(enginePin.removePrefix("v"))
    .get()

android {
    namespace = "io.github.corviddb.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The staged pairs (scripts/build-native-android.sh). Assembling
    // without them produces an AAR with NO jniLibs — the unit test below
    // fails loudly in exactly that case.
    sourceSets["main"].jniLibs.srcDir("../build/natives-android-jnilibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        // No resources, no manifest surface; the AAR is classes + jniLibs.
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The shared wrapper sources — compiled against android.jar. NativeLoading
// (java.nio.file, POSIX attribute views) is API 26+, matching minSdk.
android.sourceSets["main"].kotlin.srcDir("../src/main/kotlin")

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// The AAR structure gate (runs on the JVM): the assembled AAR must carry
// both ABI pairs and the compiled wrapper classes — the exact contract
// Maven Central consumers get. (withType: AGP creates its unit-test task
// lazily, so the plain `tasks.test` accessor does not resolve.)
tasks.withType<Test>().configureEach {
    dependsOn("assembleRelease")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

// Sources jar (the shared wrapper + any android-local sources).
val sourcesJar = tasks.register<Jar>("sourcesJar") {
    group = "publishing"
    description = "Bundle the wrapper sources compiled into the AAR"
    archiveClassifier.set("sources")
    from(file("../src/main/kotlin"))
    from(file("src/main/kotlin"))
}

// Javadoc placeholder — same ruling as the root build (Central's javadoc
// artifact requirement; the project is Kotlin-only and generates none).
val generateJavadocPlaceholder = tasks.register("generateJavadocPlaceholder") {
    val out = layout.buildDirectory.dir("javadoc-placeholder")
    outputs.dir(out)
    doLast {
        val file = out.get().file("README.md").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            corvid-android — API documentation placeholder
            ==============================================

            This jar satisfies Maven Central's javadoc artifact requirement;
            the project is Kotlin-only and generates no javadoc.

            The binding's contract documentation:

              - canonical engine + C-ABI docs: https://corvid-db.github.io/docs/
              - binding architecture, JNI discipline, the Android AAR:
                docs/PLAN.md (in the sources jar and the repository)
              - API overview + quick start: README.md (repository root)

            The Kotlin API surface (Db, Collection, Query, Field, ...) is the
            same wrapper the corvid-jvm artifact ships, compiled against
            android.jar and paired with jniLibs for arm64-v8a and x86_64.
            """.trimIndent(),
        )
    }
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    group = "publishing"
    description = "Bundle the javadoc placeholder"
    archiveClassifier.set("javadoc")
    dependsOn(generateJavadocPlaceholder)
    from(layout.buildDirectory.dir("javadoc-placeholder"))
}

// AGP creates the `release` software component in afterEvaluate, so the
// publication (and the signing that references it) is wired there.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("android") {
                artifactId = "corvid-android"
                from(components["release"])
                artifact(sourcesJar)
                artifact(javadocJar)
                pom {
                    name.set("corvid-android")
                    description.set(
                        "Android AAR for the corvid embedded database: the same Kotlin " +
                            "wrapper as corvid-jvm, compiled against android.jar, with the " +
                            "engine cdylib + JNI shim bundled as jniLibs (arm64-v8a, x86_64).",
                    )
                    url.set("https://github.com/corvid-db/corvid-jvm")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("corvid-db")
                            name.set("corvid-db")
                            url.set("https://github.com/corvid-db")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/corvid-db/corvid-jvm.git")
                        developerConnection.set("scm:git:ssh://github.com/corvid-db/corvid-jvm.git")
                        url.set("https://github.com/corvid-db/corvid-jvm")
                    }
                }
            }
        }
        repositories {
            // The SAME staging tree the root build fills (build/staging at the
            // repo root) — the release workflow uploads one bundle holding
            // corvid-jvm AND corvid-android under io/github/corvid-db/.
            maven {
                name = "staging"
                url = uri(file("../build/staging"))
            }
        }
    }

    signing {
        // Same in-memory-key wiring as the root build: unsigned locally,
        // signed on the release path where the workflow injects the key.
        val key = findProperty("signingKey") as String?
        val passphrase = findProperty("signingPassword") as String?
        if (!key.isNullOrEmpty() && !passphrase.isNullOrEmpty()) {
            useInMemoryPgpKeys(key, passphrase)
            sign(publishing.publications["android"])
        }
    }
}
