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

java {
    toolchain {
        // Compile against any available JDK; the bytecode floor is 17.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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
