// corvid-jvm — Kotlin/JVM binding for the corvid embedded database.
//
// Toolchain policy (docs/PLAN.md): Kotlin 2.2.x (current stable line),
// JVM bytecode 17 (the LTS floor), CI legs on 21 + 17, Gradle 8.14+
// (wrapper-pinned). The JNI shim is built by scripts/build-native.sh /
// .ps1 (fetch + cc/clang/MSVC) BEFORE tests run — Gradle itself never
// compiles C.
plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
    signing
    // Dokka: the hosted API reference (docs.yml publishes dokkaHtml
    // to GitHub Pages — corvid-db.github.io/corvid-jvm).
    id("org.jetbrains.dokka") version "2.0.0"
}

repositories {
    mavenCentral()
}

// ---- coordinates + version: the engine's release cascade ----------------
// Engine tag v0.4.0 -> io.github.corvid-db:corvid-jvm:0.4.0 (the binding
// version rides the engine pin; registry.tsv parity — corvid-jvm carries
// no version file of its own). The default is DERIVED from fetch.sh's
// CORVID_VERSION (the one variable that owns the pin) so the two cannot
// drift; the release workflow additionally verifies the pushed tag
// matches the pin and publishes with -Pversion=<tag-without-v>.
val enginePin: String = providers.fileContents(layout.projectDirectory.file("fetch.sh"))
    .asText.get()
    .lineSequence()
    .first { it.startsWith("CORVID_VERSION=") }
    .substringAfter('"').substringBefore('"')

group = "io.github.corvid-db"
version = providers.gradleProperty("version")
    .orElse(enginePin.removePrefix("v"))
    .get()

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No java-toolchain pin: the build is pure Kotlin (jvmTarget 17 above),
// and a pinned toolchain would demand a SECOND JDK on a leg whose
// runner JVM is 21 (Gradle cannot provision one without extra repos —
// the Windows jdk21 CI leg tripped exactly there). Instead the 17
// bytecode floor is enforced with --release, checked against whichever
// JDK runs Gradle (21 or 17 on CI).
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

sourceSets {
    // The six example programs live in examples/ as one extra source
    // set (quickstart, hybrid, VectorIndex, TextSearch, Graph, Geo) —
    // each has its own main; the CI "examples tour" runs them all.
    val examples by creating {
        kotlin.srcDir("examples")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

// The directory holding the JNI shim + cdylib (scripts/build-native.*).
val nativeDir = projectDir.resolve("build/native").absolutePath

tasks.test {
    useJUnitPlatform()
    systemProperty("corvid.native.dir", nativeDir)
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
    // The golden fixtures are read relative to the project dir.
    workingDir = projectDir
}

// One JavaExec task per example + the aggregate `examples` task CI runs.
// (Files are examples/<Name>.kt with `package corvid.examples`, so each
// main class is corvid.examples.<Name>Kt.)
val exampleNames = listOf("Quickstart", "Hybrid", "VectorIndex", "TextSearch", "Graph", "Geo")
exampleNames.forEach { name ->
    tasks.register<JavaExec>("example$name") {
        group = "examples"
        description = "Run the $name example"
        mainClass.set("corvid.examples.${name}Kt")
        classpath = sourceSets["main"].runtimeClasspath + sourceSets["examples"].runtimeClasspath
        systemProperty("corvid.native.dir", nativeDir)
    }
}

tasks.register("examples") {
    group = "examples"
    description = "Run all six examples (the CI tour)"
    dependsOn(exampleNames.map { "example$it" })
}

// ===========================================================================
// Publishing to Maven Central (Central Portal; docs/maven-central-setup.md)
// ===========================================================================
// Artifact shape (the locked ruling): ONE empty-of-natives main jar (the
// classes), sources + javadoc jars, and ONE classifier jar per platform
// bundling BOTH the compiled JNI shim AND the fetched engine cdylib at
// the jar root — the loader (corvid.jni.NativeLoading) extracts the pair
// from the classpath to a temp dir and System.load()s them, so a
// consumer needs nothing but the dependency. The engine publishes
// cdylibs for exactly these five targets (NOT android — the AAR
// follow-up is re-triggered to the first Android consumer; PLAN.md).
data class NativePlatform(val classifier: String, val engine: String, val shim: String)

val nativePlatforms = listOf(
    NativePlatform("macos-arm64", "libcorvid.dylib", "libcorvidjni.dylib"),
    NativePlatform("macos-x64", "libcorvid.dylib", "libcorvidjni.dylib"),
    NativePlatform("linux-x64", "libcorvid.so", "libcorvidjni.so"),
    NativePlatform("linux-arm64", "libcorvid.so", "libcorvidjni.so"),
    NativePlatform("windows-x64", "corvid.dll", "corvidjni.dll"),
)

// build/natives/<classifier>/ holds each platform's pair:
//   - the release workflow's matrix legs compile/fetch their pair and
//     upload it (actions/artifact); the publish job downloads all five
//     into place BEFORE Gradle runs;
//   - locally, assembleHostNatives copies build/native (the host shim
//     built by scripts/build-native.sh after ./fetch.sh) into the HOST
//     classifier's dir — publishToMavenLocal dry-runs with the host
//     pair; cross-platform jars register only when their dir exists.
val nativesRoot = layout.buildDirectory.dir("natives")

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> null
}
val hostClassifier = when {
    hostOs.contains("mac") && hostArch != null -> "macos-$hostArch"
    hostOs.contains("linux") && hostArch != null -> "linux-$hostArch"
    hostOs.contains("win") && hostArch == "x64" -> "windows-x64"
    else -> null
}

if (hostClassifier != null) {
    tasks.register<Copy>("assembleHostNatives") {
        group = "build"
        description = "Copy the host-built pair (build/native) into ${nativesRoot.get().dir(hostClassifier)}"
        // Copy, NOT Sync: in the release publish job build/native is
        // absent, and a Sync from an empty source would WIPE the
        // downloaded classifier dirs. A Copy with a missing source is a
        // no-op.
        from(projectDir.resolve("build/native"))
        into(nativesRoot.map { it.dir(hostClassifier) })
    }
}

fun classifierCamel(classifier: String): String =
    classifier.split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

// One Jar per platform whose pair is available (host always; crosses
// when the release job delivered them). Empty pairs make empty jars —
// build the shim (./fetch.sh + scripts/build-native.sh) first locally.
val nativesJarTasks = nativePlatforms.mapNotNull { platform ->
    val dirExists = nativesRoot.map { it.dir(platform.classifier).asFile.isDirectory }
    val isHost = platform.classifier == hostClassifier
    if (!isHost && !dirExists.get()) return@mapNotNull null
    tasks.register<Jar>("nativesJar${classifierCamel(platform.classifier)}") {
        group = "publishing"
        description = "Bundle the ${platform.classifier} shim + engine cdylib (classifier jar)"
        archiveClassifier.set(platform.classifier)
        from(nativesRoot.map { it.dir(platform.classifier) })
        if (isHost) {
            dependsOn("assembleHostNatives")
        }
    }
}

// Sources jar (the Kotlin sources of the API + the internal loader).
val sourcesJar = tasks.register<Jar>("sourcesJar") {
    group = "publishing"
    description = "Bundle the main sources"
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

// Javadoc jar: this project has no Java sources, so no javadoc tool runs.
// The binding's contract docs live in the canonical docs site + PLAN.md
// (README's pointers); this placeholder satisfies Central's javadoc
// artifact requirement without pulling a doc-generator dependency into
// the build (no-network-at-build-time posture stays intact).
val generateJavadocPlaceholder = tasks.register("generateJavadocPlaceholder") {
    val out = layout.buildDirectory.dir("javadoc-placeholder")
    outputs.dir(out)
    doLast {
        val file = out.get().file("README.md").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            corvid-jvm — API documentation placeholder
            =========================================

            This jar satisfies Maven Central's javadoc artifact requirement;
            the project is Kotlin-only and generates no javadoc.

            The binding's contract documentation:

              - canonical engine + C-ABI docs: https://corvid-db.github.io/docs/
              - binding architecture, JNI discipline, lifetime mapping:
                docs/PLAN.md (in the sources jar and the repository)
              - API overview + quick start: README.md (repository root)

            The Kotlin API surface (Db, Collection, Query, Field, ...) is a
            thin layer over the frozen C ABI; every semantic the API encodes
            is documented in the places above.
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(javadocJar)
            nativesJarTasks.forEach { artifact(it) }
            pom {
                name.set("corvid-jvm")
                description.set(
                    "Kotlin/JVM binding for the corvid embedded database: a thin C JNI " +
                        "shim over corvid's published C-ABI artifacts, with an idiomatic " +
                        "Kotlin API on top. Consumable from Java unchanged.",
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
        // The local bundle the release workflow uploads through the
        // Central Portal action (maven repo layout: io/github/corvid-db/...).
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
}

signing {
    // GPG via the Gradle signing plugin with an in-memory key (the
    // standard ORG_GRADLE_PROJECT_* wiring): the release workflow sets
    // ORG_GRADLE_PROJECT_signingKey / ORG_GRADLE_PROJECT_signingPassword
    // from repo secrets, which Gradle surfaces as project properties.
    // Without a key (local dry runs, plain CI) artifacts publish
    // unsigned — Central's signature requirement applies to the release
    // path only, which always has the key.
    val key = findProperty("signingKey") as String?
    val passphrase = findProperty("signingPassword") as String?
    if (!key.isNullOrEmpty() && !passphrase.isNullOrEmpty()) {
        useInMemoryPgpKeys(key, passphrase)
        sign(publishing.publications["maven"])
    }
}
