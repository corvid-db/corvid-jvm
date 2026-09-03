// Corvid.kt — native-library loading + the FFI version gate.
//
// The loader is explicit and deterministic (PLAN ruling 9 — no
// finalizers, no magic): the engine cdylib is System.load()ed from an
// absolute path FIRST, then the JNI shim, so the shim's link against
// libcorvid resolves from the already-loaded module on every platform
// (no PATH / java.library.path games). Search order for the pair
// (mechanics in corvid.jni.NativeLoading):
//
//   1. the corvid.native.dir system property
//   2. the CORVID_NATIVE_DIR environment variable
//   3. the classpath — the published per-platform CLASSIFIER jars
//      (io.github.corvid-db:corvid-jvm:<v>:<classifier>) bundle the
//      shim + engine cdylib at the jar root; both are extracted to a
//      temp dir and loaded from absolute paths (the consumer path: the
//      dependency is the only requirement)
//   4. well-known local build/fetch outputs: build/native, deps/current
//      (a dev checkout: ./fetch.sh + scripts/build-native.sh)
//   5. the classic java.library.path lookup, last (dev builds that put
//      the pair on the library path)
//
// CI and Gradle set (1); a consumer sets nothing (3 does it); a dev
// working in this repo gets (4) for free.
package corvid

import corvid.jni.NativeLoading
import java.io.File
import java.io.IOException
import java.util.UUID

object Corvid {

    /** The ABI version this binding is written against (FFI.md §4.1/§8). */
    const val EXPECTED_FFI_VERSION = 1

    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loadOnce()
        loaded = true
    }

    /** The loaded cdylib's ABI version (verified == [EXPECTED_FFI_VERSION] at load). */
    val ffiVersion: Int
        get() {
            load()
            return corvid.jni.Natives.nFfiVersion()
        }

    private fun loadOnce() {
        // 0. Android (the corvid-android AAR): the pair ships as jniLibs
        // (arm64-v8a / x86_64) inside the AAR, installed by the package
        // manager into the app's nativeLibraryDir — System.loadLibrary is
        // the classloader-namespace lookup that finds them there. Engine
        // first, same order as everywhere else. A miss (pair absent)
        // falls through to the desktop paths so a desktop JVM that merely
        // looks Android-like is never stranded.
        if (NativeLoading.onAndroid && androidJniLibs()) {
            verifyFfiVersion()
            return
        }
        val jni = findNativeLibrary()
            ?: throw IllegalStateException(
                "corvid: cannot locate the JNI shim (${corvid.jni.NativeLoading.shimName}). " +
                    "Add this platform's classifier jar to the runtime classpath " +
                    "(io.github.corvid-db:corvid-jvm:<version>:" +
                    "${corvid.jni.NativeLoading.classifier ?: "<platform>"})" +
                    ", or set -Dcorvid.native.dir=<dir> or CORVID_NATIVE_DIR, or run " +
                    "scripts/build-native.sh after ./fetch.sh.",
            )
        // The engine cdylib sits beside the shim (build scripts and the
        // classifier jars always pair them); loading it first makes the
        // shim's unresolved link resolve from the loaded module on all
        // platforms.
        val engine = NativeLoading.engineBeside(jni)
        if (engine != null) {
            System.load(engine.absolutePath)
        }
        System.load(jni.absolutePath)
        verifyFfiVersion()
    }

    /** The jniLibs pair from the corvid-android AAR, or false when absent. */
    private fun androidJniLibs(): Boolean = try {
        System.loadLibrary("corvid")
        System.loadLibrary("corvidjni")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    private fun verifyFfiVersion() {
        val v = corvid.jni.Natives.nFfiVersion()
        check(v == EXPECTED_FFI_VERSION) {
            "corvid: FFI version mismatch — libcorvid reports $v, this binding " +
                "requires $EXPECTED_FFI_VERSION (wrong cdylib on the path?)"
        }
    }

    private fun findNativeLibrary(): File? {
        // 1/2. explicit overrides (CI, Gradle, an embedding consumer).
        val explicit = buildList {
            System.getProperty("corvid.native.dir")?.let { add(it) }
            System.getenv("CORVID_NATIVE_DIR")?.let { add(it) }
        }
        NativeLoading.findInDirs(explicit)?.let { return it }

        // 3. the published classifier jars on the classpath.
        fromClasspath()?.let { return it }

        // 4. a dev checkout's own build/fetch outputs.
        NativeLoading.findInDirs(listOf("build/native", "deps/current"))?.let { return it }

        // 5. last resort: the classic java.library.path lookup.
        return try {
            System.loadLibrary("corvidjni")
            File("corvidjni") // loaded already; marker, unused
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /**
     * The classpath pair, extracted into a temp dir that is REUSED
     * across JVM runs when it is verifiably this user's own
     * (NativeLoading.reusableNativeDir — a hostile local user must not
     * be able to pre-place or swap a library we will System.load(); a
     * reused file of the same size is left untouched, so the common
     * case writes nothing). A contended shared dir (e.g. Windows holds
     * the previously loaded file locked while a different version must
     * land) falls back to a private per-process dir.
     */
    private fun fromClasspath(): File? {
        val loader = Thread.currentThread().contextClassLoader
            ?: Corvid::class.java.classLoader
        val tmp = NativeLoading.reusableNativeDir("corvid-jvm-native")
        try {
            return NativeLoading.extractFromClasspath(tmp, loader)
        } catch (first: IOException) {
            val priv = File(
                System.getProperty("java.io.tmpdir"),
                "corvid-jvm-native-${UUID.randomUUID()}",
            )
            if (!priv.isDirectory && !priv.mkdirs() && !priv.isDirectory) {
                throw IllegalStateException(
                    "corvid: found the classifier jar's natives on the classpath but " +
                        "could not create an extraction dir (${priv.absolutePath})",
                    first,
                )
            }
            try {
                return NativeLoading.extractFromClasspath(priv, loader)
            } catch (second: IOException) {
                throw IllegalStateException(
                    "corvid: found the classifier jar's natives on the classpath but " +
                        "could not extract them into ${priv.absolutePath}",
                    second,
                )
            }
        }
    }
}
