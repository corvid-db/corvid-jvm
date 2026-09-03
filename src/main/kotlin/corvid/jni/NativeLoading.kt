// NativeLoading.kt — the file-level mechanics behind Corvid.load().
//
// INTERNAL (PLAN: no raw pointers or native paths in the public API).
// Two search modes feed Corvid.loadOnce's order:
//
//   - directory scans (explicit -Dcorvid.native.dir / CORVID_NATIVE_DIR,
//     then the dev-checkout outputs build/native and deps/current) —
//     the dev/CI path that has existed since JVM1;
//   - classpath extraction (the published-artifact path): the
//     per-platform CLASSIFIER jars (build.gradle.kts `nativePlatforms`)
//     bundle BOTH the JNI shim and the engine cdylib at the jar root,
//     so a consumer needs nothing but the dependency. Both natives are
//     extracted to a temp dir and System.load()ed from ABSOLUTE paths
//     (engine first, then the shim — the shim's link against libcorvid
//     resolves from the already-loaded module on every platform); the
//     java.library.path fallback remains the last resort for dev builds
//     that drop the pair on the library path.
//
// The extraction is self-consistent per JVM: the pair is looked up on
// THIS JVM's classpath by THIS platform's file names, so a foreign
// platform's classifier jar is never loaded. Extracted files are staged
// to unique temporaries and atomically moved into place (concurrent
// JVMs racing the same shared dir last-write-win with identical bytes;
// an already-extracted file of the same size is reused untouched, which
// also keeps the common cross-JVM case from fighting Windows' lock on
// loaded libraries).
package corvid.jni

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal object NativeLoading {

    // ---- this JVM's platform ----------------------------------------------
    // The classifier string MUST match the published jars (the
    // `nativePlatforms` table in build.gradle.kts: macos-arm64,
    // macos-x64, linux-x64, linux-arm64, windows-x64).

    private val osName = System.getProperty("os.name")?.lowercase() ?: ""

    /** "macos" | "linux" | "windows" (anything unrecognized maps to linux-style .so naming). */
    val os: String = when {
        osName.contains("mac") -> "macos"
        osName.contains("win") -> "windows"
        else -> "linux"
    }

    /** "arm64" | "x64" | null (unknown archs have no published classifier). */
    val arch: String? = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> null
    }

    /** The published classifier for this JVM, or null on an unpublishable arch. */
    val classifier: String? = when {
        os == "macos" && arch != null -> "macos-$arch"
        os == "linux" && arch != null -> "linux-$arch"
        os == "windows" && arch == "x64" -> "windows-x64"
        else -> null
    }

    /** The engine cdylib's bundled file name on this platform. */
    val engineName: String = when (os) {
        "macos" -> "libcorvid.dylib"
        "windows" -> "corvid.dll"
        else -> "libcorvid.so"
    }

    /** The JNI shim's bundled file name on this platform. */
    val shimName: String = when (os) {
        "macos" -> "libcorvidjni.dylib"
        "windows" -> "corvidjni.dll"
        else -> "libcorvidjni.so"
    }

    /**
     * Android (ART/Dalvik — the corvid-android AAR consumer): true when
     * this JVM is a Dalvik-descended VM. On Android the pair ships as
     * jniLibs inside the AAR, so the Android loader path is
     * System.loadLibrary (the classloader-namespace lookup over
     * nativeLibraryDir) — extraction/classifier jars do not apply.
     * Detection is pure java.* (no android.* import): the shared sources
     * compile for the desktop JVM unchanged. Modern ART reports
     * "Dalvik" as java.vm.name for compatibility; "ART" covers the rest.
     */
    val onAndroid: Boolean = run {
        val vm = System.getProperty("java.vm.name")?.lowercase() ?: ""
        vm.contains("dalvik") || vm.contains("art")
    }

    /** Recognizable names on ANY platform — directory scans are permissive
     *  (the dev dirs hold whatever the host compiled), the classpath lookup
     *  is not (foreign classifier jars must not load). */
    val anyEngineNames = listOf("libcorvid.dylib", "libcorvid.so", "corvid.dll")
    val anyShimNames = listOf("libcorvidjni.dylib", "libcorvidjni.so", "corvidjni.dll")

    // ---- directory scans (dev / CI) ---------------------------------------

    /** First shim found under [dirs] (scanning every platform's name), or null. */
    fun findInDirs(dirs: List<String>): File? =
        dirs.firstNotNullOfOrNull { findInDir(File(it)) }

    fun findInDir(dir: File): File? {
        if (!dir.isDirectory) return null
        val name = anyShimNames.firstOrNull { File(dir, it).isFile } ?: return null
        return File(dir, name)
    }

    /** The engine cdylib sitting beside [shim] (build scripts and the
     *  classifier jars always pair them), or null. */
    fun engineBeside(shim: File): File? {
        val dir = shim.parentFile ?: return null
        val name = anyEngineNames.firstOrNull { File(dir, it).isFile } ?: return null
        return File(dir, name)
    }

    // ---- classpath extraction (published classifier jars) ------------------

    /**
     * A directory safe to extract natives into and REUSE across JVM
     * runs: `<tmpdir>/<name>` when this JVM can prove the directory is
     * ours alone — created atomically by this call, or already present
     * AND owned by the current user with no group/other write bit (a
     * hostile local user must not be able to pre-place or swap a
     * library that will be System.load()ed; /tmp is sticky, so nobody
     * can hijack a directory someone else owns, and a 755 directory's
     * files cannot be replaced by others). Anything unverifiable falls
     * back to a fresh per-process temp directory (0700, unguessable
     * name). On non-POSIX systems java.io.tmpdir is per-user already,
     * so the shared directory stands.
     */
    fun reusableNativeDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), name)
        if (!dir.exists()) {
            try {
                // Single-level create: atomic, so either WE made it or
                // someone else did — never a race about who owns it.
                Files.createDirectory(dir.toPath())
                tightenPermissions(dir)
                return dir
            } catch (e: FileAlreadyExistsException) {
                // lost a same-user race — fall through to the checks
            } catch (e: IOException) {
                // cannot create (permissions? exists as a file?) — private dir
                return privateNativeDir(name)
            }
        }
        return if (dir.isDirectory && ownedAndLockedDown(dir)) dir
        else privateNativeDir(name)
    }

    private fun privateNativeDir(name: String): File = try {
        Files.createTempDirectory(name).toFile()
    } catch (e: IOException) {
        // Last resort: the requested dir itself; any extraction
        // failure surfaces from extractFromClasspath with full context.
        File(System.getProperty("java.io.tmpdir"), name)
    }

    private fun tightenPermissions(dir: File) {
        try {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
        } catch (e: UnsupportedOperationException) {
            // non-POSIX: java.io.tmpdir is per-user by construction
        } catch (e: IOException) {
            // best effort — the ownership check below still gates reuse
        }
    }

    private fun ownedAndLockedDown(dir: File): Boolean = try {
        val attrs = Files.readAttributes(dir.toPath(), PosixFileAttributes::class.java)
        val perms = attrs.permissions()
        val user = System.getProperty("user.name")
        user != null && attrs.owner().name == user &&
            !perms.contains(PosixFilePermission.OTHERS_WRITE) &&
            !perms.contains(PosixFilePermission.GROUP_WRITE)
    } catch (e: UnsupportedOperationException) {
        // non-POSIX: per-user tmpdir is the platform's own guarantee
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Extracts THIS platform's engine + shim pair from [loader]'s
     * classpath into [targetDir] and returns the extracted shim's
     * absolute File — or null when this platform's pair is not on the
     * classpath at all (nothing is written in that case). Both files
     * must be found BEFORE anything is written, so a half-pair on the
     * classpath never produces a half-extracted dir.
     *
     * @throws IOException the pair was found but could not be written
     */
    fun extractFromClasspath(targetDir: File, loader: ClassLoader): File? {
        val engineUrl = loader.getResource(engineName) ?: return null
        val shimUrl = loader.getResource(shimName) ?: return null
        extract(engineUrl, File(targetDir, engineName))
        extract(shimUrl, File(targetDir, shimName))
        return File(targetDir, shimName)
    }

    /**
     * Copies [url] to [target] via a unique staging file and an atomic
     * move. An existing [target] of the same size is reused untouched
     * (same content ⇒ same size for these fixed artifacts); a different
     * size means a new jar version and is overwritten.
     */
    @Throws(IOException::class)
    private fun extract(url: URL, target: File) {
        val conn = url.openConnection()
        conn.useCaches = false
        val expected = conn.contentLengthLong
        if (target.isFile && expected >= 0L && target.length() == expected) return
        val staging = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            conn.getInputStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            try {
                Files.move(
                    staging.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    staging.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            staging.delete()
        }
    }
}
