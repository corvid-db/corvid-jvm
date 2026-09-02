// corvid-jvm — Kotlin/JVM binding for the corvid embedded database.
//
// Toolchain policy (docs/PLAN.md): Kotlin 2.2.x (current stable line),
// JVM bytecode 17 (the LTS floor), CI legs on 21 + 17, Gradle 8.14+
// (wrapper-pinned). The JNI shim is built by scripts/build-native.sh /
// .ps1 (fetch + cc/clang/MSVC) BEFORE tests run — Gradle itself never
// compiles C.
plugins {
    kotlin("jvm") version "2.2.20"
}

repositories {
    mavenCentral()
}

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
