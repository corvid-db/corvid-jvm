// The on-device smoke: the AAR's jniLibs pair loads through
// Corvid.load()'s Android branch (System.loadLibrary over
// nativeLibraryDir) and the ABI answers real traffic — insert, filtered
// vector query, phrase search. Run locally against an arm64 AVD (the
// ATD image is purpose-built for this):
//
//   avdmanager create avd -n corvid-atd -k "system-images;android-34;aosp_atd;arm64-v8a" -d pixel:7
//   emulator -avd corvid-atd -no-window -no-audio &
//   ./gradlew -p android connectedDebugAndroidTest
package corvid.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import corvid.Corvid
import corvid.Metric
import corvid.field
import corvid.openMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeInstrumentedTest {

    @Test
    fun loadInsertQueryRoundTrip() {
        assertEquals("FFI version gate", 1, Corvid.ffiVersion)
        openMemory().use { db ->
            val docs = db.collection("docs")
            docs.insert(
                "s1".toByteArray(),
                mapOf(
                    "kind" to "doc",
                    "body" to "rust embedded database",
                    "v" to floatArrayOf(1.0f, 0.0f),
                ),
            )
            docs.insert(
                "s2".toByteArray(),
                mapOf(
                    "kind" to "doc",
                    "body" to "python web frameworks",
                    "v" to floatArrayOf(0.0f, 1.0f),
                ),
            )
            val rows = docs.query()
                .filter(field("kind").eq("doc"))
                .vector("v", floatArrayOf(1.0f, 0.0f), 2, Metric.COSINE)
                .run()
            assertEquals(listOf("s1", "s2"), rows.map { String(it.key) })
            assertTrue(docs.phraseSearch("body", "embedded database", 2).isNotEmpty())
        }
    }
}
