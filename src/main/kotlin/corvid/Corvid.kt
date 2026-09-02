// Corvid.kt — native-library loading + the FFI version gate.
//
// The loader is explicit and deterministic (PLAN ruling 9 — no
// finalizers, no magic): the engine cdylib is System.load()ed from an
// absolute path FIRST, then the JNI shim, so the shim's link against
// libcorvid resolves from the already-loaded module on every platform
// (no PATH / java.library.path games). Search order for the directory:
//
//   1. the corvid.native.dir system property
//   2. the CORVID_NATIVE_DIR environment variable
//   3. well-known local build/fetch outputs: build/native, deps/current
//
// CI and Gradle set (1); a consumer embedding corvid-jvm sets (1) or
// (2), or drops the two libraries on java.library.path (the loader
// falls back to System.loadLibrary("corvidjni") last).
package corvid

import java.io.File

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
        val jni = findNativeLibrary()
            ?: throw IllegalStateException(
                "corvid: cannot locate the JNI shim (libcorvidjni.dylib / " +
                    "libcorvidjni.so / corvidjni.dll). Set -Dcorvid.native.dir=<dir> " +
                    "or CORVID_NATIVE_DIR, or run scripts/build-native.sh after ./fetch.sh.",
            )
        // The engine cdylib sits beside the shim (build scripts put it
        // there); loading it first makes the shim's unresolved link
        // resolve from the loaded module on all platforms.
        val engine = engineLibBeside(jni.parentFile)
        if (engine != null) {
            System.load(engine.absolutePath)
        }
        System.load(jni.absolutePath)
        val v = corvid.jni.Natives.nFfiVersion()
        check(v == EXPECTED_FFI_VERSION) {
            "corvid: FFI version mismatch — libcorvid reports $v, this binding " +
                "requires $EXPECTED_FFI_VERSION (wrong cdylib on the path?)"
        }
    }

    private fun findNativeLibrary(): File? {
        val names = listOf("libcorvidjni.dylib", "libcorvidjni.so", "corvidjni.dll")
        val dirs = mutableListOf<String>()
        System.getProperty("corvid.native.dir")?.let { dirs.add(it) }
        System.getenv("CORVID_NATIVE_DIR")?.let { dirs.add(it) }
        dirs.add("build/native")
        dirs.add("deps/current")
        for (d in dirs) {
            for (n in names) {
                val f = File(d, n)
                if (f.isFile) return f
            }
        }
        // Last resort: the classic java.library.path lookup.
        return try {
            System.loadLibrary("corvidjni")
            File("corvidjni") // loaded already; marker, unused
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    private fun engineLibBeside(dir: File): File? {
        val names = listOf("libcorvid.dylib", "libcorvid.so", "corvid.dll")
        for (n in names) {
            val f = File(dir, n)
            if (f.isFile) return f
        }
        return null
    }
}
