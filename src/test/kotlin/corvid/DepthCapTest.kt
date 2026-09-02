// DepthCapTest.kt — the encode-side nesting cap (the python-F1 class,
// accepted-review fix): encode caps at the engine's decode bound,
// corvid::value::MAX_NESTING (128) — mirrored as CORVID_JNI_MAX_NESTING
// in native/corvid_jni.c (the whole-document path, where the recursion
// is in C and an uncapped list would smash the NATIVE stack) and
// Values.MAX_NESTING (the putMany handle path). Converter-accepted ==
// decodable: a deeper value could be BUILT through the constructor ABI
// but the engine could never decode it back (dump/load), so both encode
// paths reject it up front as CorvidException(ErrCode.ARGUMENT).
//
// The boundary follows the engine decoder's own convention (top-level
// value is depth 0, every container's children one more): a chain of
// 128 containers round-trips; 129 is rejected cleanly.
package corvid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DepthCapTest {

    /** A chain of [wrappers] nested lists around a scalar. */
    private fun nestedLists(wrappers: Int): Any? {
        var v: Any? = 1L
        repeat(wrappers) { v = listOf(v) }
        return v
    }

    private fun expectArgument(block: () -> Unit, ctx: String) {
        try {
            block()
        } catch (e: CorvidException) {
            assertEquals(ErrCode.ARGUMENT, e.code, "$ctx: error code")
            return
        }
        fail("$ctx: expected CorvidException, got success")
    }

    @Test
    fun `129 deep list insert is a clean CorvidException not a native smash`() {
        Corvid.load()
        openMemory().use { db ->
            val c = db.collection("depth-cap-insert")
            expectArgument({ c.insert("over".toByteArray(), nestedLists(129)) },
                           "129-deep insert (the C encode_value path)")
            assertEquals(0L, c.len(), "nothing stored")
            c.close()
        }
    }

    @Test
    fun `128 deep list round-trips — the engine decode boundary is accepted`() {
        Corvid.load()
        openMemory().use { db ->
            val c = db.collection("depth-cap-edge")
            c.insert("edge".toByteArray(), nestedLists(128))
            assertEquals(1L, c.len(), "128-deep stored + readable")
            // Walk back down to the scalar.
            var cur: Any? = c.get("edge".toByteArray())
            repeat(128) { cur = (cur as List<*>)[0] }
            assertEquals(1L, cur, "innermost scalar survives the round trip")
            c.close()
        }
    }

    @Test
    fun `129 containers are rejected on the putMany handle path too`() {
        Corvid.load()
        openMemory().use { db ->
            val c = db.collection("depth-cap-putmany")
            expectArgument(
                { c.putMany(listOf("over".toByteArray()), listOf(nestedLists(129))) },
                "129-deep putMany (the Values.encode path)",
            )
            assertEquals(0L, c.len(), "nothing stored")
            c.close()
        }
    }
}
