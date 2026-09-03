// NativeLoadingTest.kt — unit tests for the two load paths behind
// Corvid.load()'s search order (the published-artifact path and the
// explicit-dir dev path; PLAN "no raw pointers or native paths in the
// public API" keeps all of this internal). These are FILE-mechanics
// tests only: no real library is ever System.load()ed here — the fake
// "dylib" is a few bytes of text, and the golden suite exercises the
// actual load separately.
package corvid.jni

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLoadingTest {

    // ---- the classpath path (published classifier jars) --------------------

    @Test
    fun `extracts this platform's pair from the classpath`() {
        val jarish = tempDir() // stands in for the classifier jar root
        File(jarish, NativeLoading.engineName).writeText("fake-engine")
        File(jarish, NativeLoading.shimName).writeText("fake-shim")
        val out = tempDir()

        val shim = URLClassLoader(arrayOf(jarish.toURI().toURL())).use { loader ->
            NativeLoading.extractFromClasspath(out, loader)
        }

        assertEquals(File(out, NativeLoading.shimName), shim)
        assertEquals("fake-shim", File(out, NativeLoading.shimName).readText())
        assertEquals("fake-engine", File(out, NativeLoading.engineName).readText())
    }

    @Test
    fun `classpath without this platform's pair yields null and writes nothing`() {
        val foreign = tempDir() // a FOREIGN platform's names only — must not load
        val anyForeignShim = NativeLoading.anyShimNames.first { it != NativeLoading.shimName }
        val anyForeignEngine = NativeLoading.anyEngineNames.first { it != NativeLoading.engineName }
        File(foreign, anyForeignShim).writeText("foreign")
        File(foreign, anyForeignEngine).writeText("foreign")
        val out = tempDir()

        val shim = URLClassLoader(arrayOf(foreign.toURI().toURL())).use { loader ->
            NativeLoading.extractFromClasspath(out, loader)
        }

        assertNull(shim)
        assertTrue(out.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a same-size extracted file is reused, not rewritten`() {
        val jarish = tempDir()
        File(jarish, NativeLoading.engineName).writeText("engine-v1")
        File(jarish, NativeLoading.shimName).writeText("shim--v1") // same length as shim--v2
        val out = tempDir()
        // A previous run's files already sit in the shared dir...
        File(out, NativeLoading.engineName).writeText("engine-v0")
        File(out, NativeLoading.shimName).writeText("shim--v0") // ...same sizes
        File(out, "unrelated.txt").writeText("left alone")

        URLClassLoader(arrayOf(jarish.toURI().toURL())).use { loader ->
            NativeLoading.extractFromClasspath(out, loader)
        }

        // Same-size targets keep the incumbent bytes (the reuse rule that
        // avoids fighting Windows' lock on loaded libraries); anything
        // else is untouched.
        assertEquals("shim--v0", File(out, NativeLoading.shimName).readText())
        assertEquals("engine-v0", File(out, NativeLoading.engineName).readText())
        assertEquals("left alone", File(out, "unrelated.txt").readText())
    }

    @Test
    fun `a size change rewrites the extracted file`() {
        val jarish = tempDir()
        File(jarish, NativeLoading.engineName).writeText("engine-new-bytes")
        File(jarish, NativeLoading.shimName).writeText("shim-new")
        val out = tempDir()
        File(out, NativeLoading.shimName).writeText("old") // different size

        URLClassLoader(arrayOf(jarish.toURI().toURL())).use { loader ->
            NativeLoading.extractFromClasspath(out, loader)
        }

        assertEquals("shim-new", File(out, NativeLoading.shimName).readText())
        // No staging ".part" litter survives the move.
        assertTrue(out.listFiles()!!.none { it.name.endsWith(".part") })
    }

    // ---- the shared extraction dir (hostile-local-user posture) -----------

    @Test fun `reusable native dir is stable across calls`() {
        val a = NativeLoading.reusableNativeDir("corvid-jvm-test-reuse")
        val b = NativeLoading.reusableNativeDir("corvid-jvm-test-reuse")
        assertEquals(a, b) // created once, then the owned-and-locked path
        assertTrue(a.isDirectory)
    }

    @Test fun `a world-writable pre-existing dir is not reused`() {
        if (System.getProperty("os.name").lowercase().contains("win")) {
            return // non-POSIX tmpdir is per-user; the check is a no-op there
        }
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "corvid-jvm-test-world")
        if (!dir.isDirectory) Files.createDirectories(dir.toPath())
        Files.setPosixFilePermissions(
            dir.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"),
        )

        val chosen = NativeLoading.reusableNativeDir("corvid-jvm-test-world")

        // A directory others can write into must never receive libraries
        // we will System.load() — the loader takes a private dir instead.
        // (Running as root defeats the ownership half; CI never does.)
        if (System.getProperty("user.name") != "root") {
            assertTrue(chosen != dir, "a group/other-writable dir must not be reused")
        }
        assertTrue(chosen.isDirectory)
    }

    // ---- the explicit-dir path (dev / CI) ----------------------------------

    @Test
    fun `finds the shim and the engine beside it in an explicit dir`() {
        val dir = tempDir()
        File(dir, "libcorvidjni.dylib").writeText("fake") // any platform's names:
        File(dir, "libcorvid.dylib").writeText("fake")    // the scan is permissive

        assertEquals(File(dir, "libcorvidjni.dylib"), NativeLoading.findInDir(dir))
        assertEquals(
            File(dir, "libcorvid.dylib"),
            NativeLoading.engineBeside(File(dir, "libcorvidjni.dylib")),
        )
        assertEquals(File(dir, "libcorvidjni.dylib"), NativeLoading.findInDirs(listOf("/nonexistent", dir.path)))
    }

    @Test
    fun `dirs without a shim yield null`() {
        val dir = tempDir()
        File(dir, "readme.txt").writeText("no natives here")

        assertNull(NativeLoading.findInDir(dir))
        assertNull(NativeLoading.findInDir(File("/nonexistent")))
        assertNull(NativeLoading.engineBeside(File(dir, "libcorvidjni.dylib"))) // absent shim
    }

    private fun tempDir(): File = Files.createTempDirectory("corvid-jvm-loader-test").toFile()
}
