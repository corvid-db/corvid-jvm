// The AAR structure gate: assembling is not enough — the artifact a
// Maven Central consumer downloads must carry BOTH ABI pairs under
// jniLibs/ and the compiled wrapper classes. An AAR assembled without
// scripts/build-native-android.sh's staged pairs (or with a staging bug)
// fails here with the missing entry named.
package corvid.android

import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class AarStructureTest {

    private fun aarFile(): java.io.File {
        val dir = java.io.File("build/outputs/aar")
        val aars = dir.listFiles { f -> f.extension == "aar" }
        if (aars.isNullOrEmpty()) fail("no .aar under $dir — did assembleRelease run?")
        return aars.single()
    }

    @Test
    fun aarBundlesBothAbiPairsAndClasses() {
        val aar = aarFile()
        ZipFile(aar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val required = listOf(
                "classes.jar",
                // AARs package natives under jni/<abi>/ (merged from the
                // jniLibs source dirs); the package manager installs them
                // into the app's nativeLibraryDir.
                "jni/arm64-v8a/libcorvid.so",
                "jni/arm64-v8a/libcorvidjni.so",
                "jni/x86_64/libcorvid.so",
                "jni/x86_64/libcorvidjni.so",
            )
            val missing = required.filter { it !in names }
            if (missing.isNotEmpty()) {
                fail("${aar.name} is missing ${missing.joinToString()} — run scripts/build-native-android.sh (fetch + NDK shim) before assembling")
            }
            // No foreign ABI ever rides along (directory entries excluded —
            // the bare "jni/" folder itself carries no ABI).
            assertTrue(
                names.none { entry ->
                    entry.startsWith("jni/") && !entry.endsWith("/") && listOf("arm64-v8a/", "x86_64/").none { prefix ->
                        entry.substringAfter("jni/").startsWith(prefix)
                    }
                },
                "unexpected jni/ entries: ${names.filter { it.startsWith("jni/") }}",
            )
        }
    }
}
