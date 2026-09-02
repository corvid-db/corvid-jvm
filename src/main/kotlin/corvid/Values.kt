// Values.kt — the Kotlin-side value mapping over the raw handle layer.
//
// Public API: documents are Kotlin values mapped per docs/PLAN.md:
//
//   C null  ↔ null          C bool ↔ Boolean     C int  ↔ Long
//   C float ↔ Double        C text ↔ String      C bytes ↔ ByteArray
//   C vector ↔ FloatArray   C array ↔ List<Any?> C map ↔ LinkedHashMap
//
// NaN/±inf/-0.0 cross bit-exact and NaN payloads are preserved — the
// golden suite pins this op by op. Encode also accepts the narrower
// JVM number kinds (Int/Short/Byte/Float) for literal convenience;
// decode always yields the canonical types above. Decoded maps are
// LinkedHashMaps in the engine's ascending key-byte iteration order
// (via corvid_value_map_keys), so a decoded document is always
// COMPLETE, whatever wrote the data.
package corvid

import corvid.jni.Natives

internal object Values {

    /** The engine's decode bound — corvid::value::MAX_NESTING (128),
     * mirrored as CORVID_JNI_MAX_NESTING in native/corvid_jni.c (the
     * whole-document encode path). Converter-accepted == decodable: a
     * value deeper than this could be BUILT through the constructor ABI
     * but the engine could never decode it back (dump/load), so encode
     * rejects it up front — and, on the JNI path, before the recursion
     * in C can smash the native stack. */
    internal const val MAX_NESTING = 128

    // ---- UTF-8 wire discipline (PLAN ruling 5) ----
    internal fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    // ---- status / error plumbing (the same-call guarantee, ruling 7) ----
    fun throwLastError(): Nothing {
        val code = Natives.nLastErrorCode()
        val msgBytes = Natives.nLastErrorMessage()
        val msg = if (msgBytes != null) String(msgBytes, Charsets.UTF_8) else ""
        throw CorvidException(ErrCode.of(code), msg)
    }

    fun check(status: Int) {
        if (status != 0) throwLastError()
    }

    fun checkHandle(h: Long): Long {
        if (h == 0L) throwLastError()
        return h
    }

    // ---- encode: Kotlin value → OWNED handle (caller frees / engine
    //      call consumes). Mirrors the C-side encode_value dispatch,
    //      MAX_NESTING and all. ----
    fun encode(v: Any?): Long = encode(v, 0)

    private fun encode(v: Any?, depth: Int): Long {
        if (depth > MAX_NESTING) throw CorvidException(
            ErrCode.ARGUMENT,
            "value nesting exceeds the maximum depth of $MAX_NESTING",
        )
        return when (v) {
        null -> Natives.nValueNull()
        is Boolean -> Natives.nValueBool(v)
        is Long -> Natives.nValueInt(v)
        is Int -> Natives.nValueInt(v.toLong())
        is Short -> Natives.nValueInt(v.toLong())
        is Byte -> Natives.nValueInt(v.toLong())
        is Double -> Natives.nValueFloat(v)
        is Float -> Natives.nValueFloat(v.toDouble())
        is String -> checkHandle(Natives.nValueText(utf8(v)))
        is ByteArray -> checkHandle(Natives.nValueBytes(v))
        is FloatArray -> checkHandle(Natives.nValueVector(v))
        is List<*> -> {
            val arr = Natives.nValueArrayNew()
            for (item in v) {
                val h = encode(item, depth + 1)
                check(Natives.nValueArrayPush(arr, h)) // consumes h (§8)
            }
            arr
        }
        is Map<*, *> -> {
            val m = Natives.nValueMapNew()
            for ((k, item) in v) {
                if (k !is String) {
                    Natives.nValueFree(m)
                    throw CorvidException(
                        ErrCode.ARGUMENT,
                        "map keys must be Strings (got ${k?.javaClass})",
                    )
                }
                val h = encode(item, depth + 1)
                check(Natives.nValueMapPut(m, utf8(k), h)) // consumes h (§8)
            }
            m
        }
        else -> throw CorvidException(
            ErrCode.ARGUMENT,
            "unsupported Kotlin type ${v.javaClass.name} for a corvid value",
        )
        }
    }

    // ---- decode: (borrowed or owned) handle → Kotlin value, COMPLETE
    //      copy; maps enumerate keys through the real §4.4 iterator. ----
    fun decode(h: Long): Any? = when (Natives.nValueType(h)) {
        0 -> null                                            // Null
        1 -> decodeBool(h)
        2 -> { val ok = IntArray(1); Natives.nValueAsInt(h, ok).also { require(ok[0] == 1) } }
        3 -> { val ok = IntArray(1); Natives.nValueAsFloat(h, ok).also { require(ok[0] == 1) } }
        4 -> decodeText(h)
        5 -> Natives.nValueBytesRef(h) ?: error("bytes decode failed")
        8 -> Natives.nValueVectorRef(h) ?: error("vector decode failed")
        6 -> { // Array
            val n = Natives.nValueLen(h)
            val out = ArrayList<Any?>(n.toInt())
            for (i in 0 until n) {
                out.add(decode(Natives.nValueArrayGet(h, i)))
            }
            out
        }
        7 -> { // Map
            val n = Natives.nValueLen(h)
            val out = LinkedHashMap<String, Any?>(n.toInt())
            if (n > 0) {
                val cursor = Natives.nValueMapKeys(h)
                if (cursor == 0L) throwLastError()
                while (true) {
                    val kb = Natives.nStrsNext(cursor) ?: break
                    val key = String(kb, Charsets.UTF_8)
                    out[key] = decode(Natives.nValueMapGet(h, kb))
                }
                Natives.nStrsFree(cursor)
            }
            out
        }
        else -> throw CorvidException(ErrCode.DECODE, "unknown value type tag")
    }

    private fun decodeBool(h: Long): Boolean {
        val ok = IntArray(1)
        val v = Natives.nValueAsBool(h, ok)
        require(ok[0] == 1)
        return v
    }

    private fun decodeText(h: Long): String {
        val b = Natives.nValueTextRef(h) ?: throwLastError()
        return String(b, Charsets.UTF_8)
    }
}
