// GoldenTest.kt — the golden-suite port, corvid-jvm's port of the
// engine's reference harness (corvid-db/corvid, crates/corvid-ffi/c/
// smoke.c, MIT) as ported standalone by corvid-c/test/golden.c and
// corvid-go/golden_test.go.
//
// Same job as upstream, different moment of truth: the engine's
// harness links the cdylib cargo JUST BUILT and reads the golden/
// fixtures committed in the engine repo; this one drives the cdylib
// DOWNLOADED from the pinned GitHub release (fetch.sh / fetch.ps1 put
// it, corvid.h, and the release's golden/ under deps/) through THIS
// BINDING — the Kotlin API wherever it can express the op, the raw JNI
// handle layer (corvid.jni.Natives + Values.encode/decode) where the
// op is inherently raw (VTYPE/VLEN/VAS_*/V*_REF/VNEST/VCLONE/VPUSH/
// VPUT/VMAP_KEYS/GET_KEYS are value-handle exercises). If the published
// .so/.dylib/.dll, header, or fixtures disagree with the engine's own
// suite, THIS fails where that one stayed green — divergence is a
// finding for the engine repo, never patched around here.
//
// The mechanics are kept deliberately IDENTICAL to the C harness so the
// suites are diffable and their verdicts comparable: the same fixture
// grammar (OP<TAB>args<TAB>expected; value literals with bits:/bits32:
// NaN specials; ~x computed-double tolerance), the same dispatch table,
// the same checks, one line at a time, every line dispatched, every
// expectation checked — no softened asserts. Two counting rules carry
// over verbatim: `lines` comes from an INDEPENDENT pre-scan (so a
// dispatch loop that skips a counted line diverges from `executed`),
// and the first failure names file:line + OP + expected-vs-got.
//
// One deliberate JNI-shaped addition on the UPDATE_ABORT line (document
// where it runs): the aborting closure THROWS, and the port pins BOTH
// halves of the ruling — the marker exception surfaces at the call site
// (PLAN rule 6) AND the engine recorded CORVID_E_ARGUMENT with a
// message in the same-thread last-error slot, read immediately after.
//
// Verdict protocol: stdout (test log) carries one
// "SMOKE <file> lines=<n> executed=<n>" line per fixture.

package corvid

import corvid.jni.Natives
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
// The Scenario helpers below are named openMemory/openFile; alias the
// package-level openers so the call inside openMemory() binds to the
// real corvid.openMemory (an unqualified call would resolve to the
// Unit-returning helper itself through the implicit Scenario receiver).
import corvid.openMemory as openMemoryDb
import kotlin.test.Test

class GoldenTest {

    private val marker = RuntimeException("update_abort: aborting per the fixture")

    // ------------------------------------------------------------------
    // Scenario state
    // ------------------------------------------------------------------

    private class Scenario(
        val file: String,
        var line: Int = 0,
        var op: String = "?",
        var db: Db? = null,
        var coll: Collection? = null,
        val workdir: Path,
        val dbPath: Path,
        val db2Path: Path,
        val dumpPath: Path,
        val backupPath: Path,
        var lastAutoId: Long = 0,
    )

    private fun Scenario.fail(format: String, vararg args: Any?): Nothing =
        throw AssertionError(
            "FAIL $file:${line} OP=$op: " + format.format(*args),
        )

    private fun Scenario.check(cond: Boolean, format: String, vararg args: Any?) {
        if (!cond) fail(format, *args)
    }

    private fun Scenario.expectOk(e: Throwable?) {
        if (e != null) fail("expected ok, got $e")
    }

    // Mirrors expect_err: a failure with exactly this code AND a
    // recorded message (driving the error-reporting pair through the
    // Kotlin error surface).
    private fun Scenario.expectErr(e: Throwable?, code: ErrCode) {
        if (e == null) fail("expected CORVID_ERR code ${code.value}, got success")
        if (e !is CorvidException) {
            fail("expected a CorvidException, got ${e.javaClass.name}: $e")
        }
        if (e.code != code) {
            fail("expected error code ${code.value}, got ${e.code.value} (${e.message})")
        }
        check(e.message?.isNotEmpty() == true,
              "error code ${code.value} recorded but the message is missing")
    }

    // runCatching wrapper so the OP table below reads like the C/Go
    // harness's plain calls (failures funnel into expectErr).
    private inline fun <T> attempting(block: () -> T): Result<T> =
        runCatching(block)

    private fun Scenario.closeColl() {
        coll?.close()
        coll = null
    }

    private fun Scenario.closeDb() {
        closeColl()
        db?.let { d ->
            attempting { d.close() }.onFailure { fail("close failed: $it") }
        }
        db = null
    }

    private fun Scenario.docs(): Collection {
        if (coll == null) {
            val d = checkNotNull(db) { "no database open in this scenario" }
            coll = attempting { d.collection("docs") }
                .getOrElse { fail("collection(docs) failed: $it") }
        }
        return coll!!
    }

    private fun Scenario.openMemory() {
        closeDb()
        db = attempting { openMemoryDb() }.getOrElse { fail("openMemory failed: $it") }
        docs()
    }

    private fun Scenario.openFile(path: Path) {
        closeDb()
        db = attempting { open(path.toString()) }
            .getOrElse { fail("open(${path}) failed: $it") }
        docs()
    }

    private fun Scenario.setColl(name: String) {
        closeColl()
        val c = attempting { db!!.collection(name) }
            .getOrElse { fail("collection($name) failed: $it") }
        check(c.name == name, "collection_name round trip failed")
        coll = c
    }

    // ------------------------------------------------------------------
    // Tokenizing (the C harness's split_top, verbatim)
    // ------------------------------------------------------------------

    private fun splitTop(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in 0..s.length) {
            val c = if (i < s.length) s[i] else ','
            when (c) {
                '[', '{', '(' -> depth++
                ']', '}', ')' -> depth--
            }
            if (c == ',' && depth == 0) {
                var end = i
                while (end > start && (s[end - 1] == ' ' || s[end - 1] == '\r')) end--
                if (end > start) out.add(s.substring(start, end))
                start = i + 1
            }
        }
        return out
    }

    private fun Scenario.parseI64(s: String): Long =
        s.toLongOrNull() ?: fail("bad int token %s", s)

    private fun Scenario.parseInt(s: String): Int = parseI64(s).toInt()

    // Mirrors strtoull(s, NULL, 16): optional 0x/0X prefix, then hex.
    private fun Scenario.parseHex(s: String): Long =
        s.removePrefix("0x").removePrefix("0X").toULongOrNull(16)
            ?.toLong() ?: fail("bad hex token %s", s)

    // bits:0x… (f64 from bits), inf/-inf/nan, else decimal.
    private fun Scenario.parseDouble(s: String): Double = when {
        s.startsWith("bits:") -> Double.fromBits(parseHex(s.substring(5)))
        s == "inf" -> Double.POSITIVE_INFINITY
        s == "-inf" -> Double.NEGATIVE_INFINITY
        s == "nan" -> Double.NaN
        else -> s.toDoubleOrNull() ?: fail("bad float token %s", s)
    }

    private fun doubleExact(got: Double, want: Double): Boolean =
        got.toRawBits() == want.toRawBits()

    private fun doubleNear(got: Double, want: Double): Boolean =
        abs(got - want) <= 1e-6 * (1.0 + abs(want))

    // `~x` near; `=x`/bare/bits:/inf bit-exact (NaN payloads included).
    private fun Scenario.doubleMatches(got: Double, tok: String): Boolean = when {
        tok.startsWith("~") -> doubleNear(got, parseDouble(tok.substring(1)))
        tok.startsWith("=") -> doubleExact(got, parseDouble(tok.substring(1)))
        else -> doubleExact(got, parseDouble(tok))
    }

    private fun Scenario.errToken(expected: String): ErrCode {
        check(expected.startsWith("err:"),
              "error expectation must be err:N, got %s", expected)
        val n = expected.substring(4).toIntOrNull()
            ?: fail("bad err token %s", expected)
        return ErrCode.of(n)
    }

    // ------------------------------------------------------------------
    // Value literals: parse into Kotlin values (then Values.encode
    // builds the C side — exercising the binding's value mapping)
    // ------------------------------------------------------------------

    private fun Scenario.startsWord(s: String, i: Int, word: String): Boolean {
        if (!s.startsWith(word, i)) return false
        val after = i + word.length
        if (after >= s.length) return true
        val c = s[after]
        return c == ',' || c == ']' || c == '}' || c == ' ' || c == '\r'
    }

    private fun Scenario.matchParen(s: String, open: Int): Int {
        var depth = 0
        for (q in open until s.length) {
            when (s[q]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return q
                }
            }
        }
        fail("unbalanced () in literal")
    }

    private fun closeOf(open: Char): Char = when (open) {
        '[' -> ']'
        '{' -> '}'
        else -> ')'
    }

    private fun Scenario.matchBracket(s: String, open: Int): Int {
        var depth = 0
        for (q in open until s.length) {
            if (s[q] == s[open]) depth++
            else if (s[q] == closeOf(s[open])) {
                depth--
                if (depth == 0) return q
            }
        }
        fail("unbalanced %c in literal", s[open])
    }

    private fun skipWs(s: String, i: Int): Int {
        var j = i
        while (j < s.length && (s[j] == ' ' || s[j] == '\r')) j++
        return j
    }

    // Scans one numeric literal (int vs float classified by the
    // characters seen, exactly like the C harness).
    private fun Scenario.buildNumber(s: String, i: Int): Pair<Any?, Int> {
        val start = i
        if (startsWord(s, i, "inf")) return Double.POSITIVE_INFINITY to i + 3
        if (startsWord(s, i, "-inf")) return Double.NEGATIVE_INFINITY to i + 4
        if (startsWord(s, i, "nan")) return Double.NaN to i + 3
        var j = i
        var isFloat = false
        var isBits = false
        if (s.startsWith("bits:", j)) {
            isFloat = true
            isBits = true
            j += 5 // scan the hex payload only
        }
        scan@ while (j < s.length) {
            val c = s[j]
            when {
                c in '0'..'9' || c == '-' || c == '+' -> j++
                c == '.' || c == 'e' || c == 'E' -> {
                    isFloat = true
                    j++
                }
                isBits && (c in 'a'..'f' || c in 'A'..'F' || c == 'x' || c == 'X') -> j++
                else -> break@scan
            }
        }
        val tok = s.substring(start, j)
        if (tok.isEmpty()) fail("empty numeric literal")
        if (isBits) return parseDouble(tok) to j // re-include the prefix
        if (isFloat) {
            return (tok.toDoubleOrNull() ?: fail("bad float literal %s", tok)) to j
        }
        return (tok.toLongOrNull() ?: fail("bad int literal %s", tok)) to j
    }

    private fun Scenario.buildLit(s: String, i: Int): Pair<Any?, Int> {
        val i0 = skipWs(s, i)
        if (i0 >= s.length) fail("empty literal")
        val c = s[i0]

        if (c == '-' || c in '0'..'9') return buildNumber(s, i0)
        // bits:/inf/-inf/nan start with letters but are NUMBERS; they
        // must win over the b(...)/t(...) literal heads.
        if (s.startsWith("bits:", i0) || startsWord(s, i0, "inf") ||
            startsWord(s, i0, "-inf") || startsWord(s, i0, "nan")
        ) return buildNumber(s, i0)
        if (startsWord(s, i0, "null")) return null to i0 + 4
        if (startsWord(s, i0, "true")) return true to i0 + 4
        if (startsWord(s, i0, "false")) return false to i0 + 5

        if ((c == 't' || c == 'b') && i0 + 1 < s.length && s[i0 + 1] == '(') {
            val close = matchParen(s, i0 + 1)
            val body = s.substring(i0 + 2, close)
            return if (c == 't') body to close + 1
            else body.toByteArray(Charsets.ISO_8859_1) to close + 1
        }
        if (s.startsWith("vec(", i0)) {
            val close = matchParen(s, i0 + 3)
            return buildVec(s.substring(i0 + 4, close)) to close + 1
        }

        if (c == '[') {
            val close = matchBracket(s, i0)
            val arr = mutableListOf<Any?>()
            var j = i0 + 1
            while (j < close) {
                val (item, nj) = buildLit(s, j)
                arr.add(item)
                j = skipWs(s, nj)
                if (j < close && s[j] == ',') j++
            }
            return arr to close + 1
        }

        if (c == '{') {
            val close = matchBracket(s, i0)
            val m = LinkedHashMap<String, Any?>()
            var j = i0 + 1
            while (j < close) {
                j = skipWs(s, j)
                val ks = j
                while (j < close && s[j] != '=' && s[j] != ',' && s[j] != '}') j++
                if (j >= close || s[j] != '=') fail("map literal needs k=v pairs")
                val key = s.substring(ks, j).trimStart(' ')
                j++ // past '='
                val (v, nj) = buildLit(s, j)
                m[key] = v
                j = skipWs(s, nj)
                if (j < close && s[j] == ',') j++
            }
            return m to close + 1
        }

        val snippet = s.substring(i0, minOf(i0 + 24, s.length))
        fail("unparseable literal at %s", snippet)
    }

    private fun Scenario.buildVec(body: String): FloatArray {
        val toks = splitTop(body)
        return FloatArray(toks.size) { i ->
            val tk = toks[i]
            if (tk.startsWith("bits32:")) {
                Float.fromBits(parseHex(tk.substring(7)).toInt())
            } else {
                parseDouble(tk).toFloat()
            }
        }
    }

    private fun Scenario.lit(s: String): Any? = buildLit(s, 0).first

    // Builds an OWNED handle from a literal token (Values.encode → the
    // raw value family; the harness frees it).
    private fun Scenario.encode(literal: String): Long =
        Values.encode(lit(literal))

    // ------------------------------------------------------------------
    // Structural comparison of Kotlin-side values (bit-exact floats —
    // the decode side of the C harness's read-API comparison)
    // ------------------------------------------------------------------

    private fun valuesEqual(a: Any?, b: Any?): Boolean = when (a) {
        null -> b == null
        is Boolean -> b is Boolean && a == b
        is Long -> b is Long && a == b
        is Double -> b is Double && a.toRawBits() == b.toRawBits()
        is String -> b is String && a == b
        is ByteArray -> b is ByteArray && a.contentEquals(b)
        is FloatArray -> b is FloatArray && a.size == b.size &&
            a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
        is List<*> -> b is List<*> && a.size == b.size &&
            a.indices.all { valuesEqual(a[it], b[it]) }
        is Map<*, *> -> b is Map<*, *> && a.size == b.size &&
            a.all { (k, v) -> b.containsKey(k) && valuesEqual(v, b[k]) }
        else -> false
    }

    private fun Scenario.checkValue(got: Any?, wantTok: String) {
        val want = lit(wantTok)
        check(valuesEqual(got, want),
              "value mismatch: got %s, want %s", render(got), render(want))
    }

    private fun render(v: Any?): String = when (v) {
        null -> "null"
        is Double -> "double(0x%016x=%g)".format(v.toRawBits(), v)
        is FloatArray -> "vec(dim=${v.size})"
        is ByteArray -> "bytes(${v.size} bytes)"
        is Map<*, *> -> "map(len=${v.size})"
        else -> v.toString()
    }

    // ------------------------------------------------------------------
    // Cursor helpers over the public API's returned rows
    // ------------------------------------------------------------------

    private fun rowKeys(rows: List<Row>): List<String> = rows.map { String(it.key, Charsets.UTF_8) }
    private fun rowScores(rows: List<Row>): List<Float> = rows.map { it.score }
    private fun bytesKeys(keys: List<ByteArray>): List<String> = keys.map { String(it, Charsets.UTF_8) }

    // Matches "k(a,b,c)" — key order exact.
    private fun Scenario.checkKeys(keys: List<String>, expected: String) {
        check(expected.length >= 3 && expected[0] == 'k' && expected[1] == '(' &&
              expected.last() == ')',
              "key expectation must be k(...), got %s", expected)
        val body = expected.substring(2, expected.length - 1)
        val want = if (body.isEmpty()) emptyList() else splitTop(body)
        check(keys.size == want.size,
              "row count ${keys.size}, expected ${want.size} ($keys)")
        for (i in want.indices) {
            check(keys[i] == want[i], "row $i key ${keys[i]}, expected ${want[i]}")
        }
    }

    // Matches a "|~s1,~s2" suffix — one double token per row.
    private fun Scenario.checkScores(scores: List<Float>, suffix: String) {
        if (suffix.isEmpty()) return
        check(suffix[0] == '|', "score suffix must start with |, got %s", suffix)
        val body = suffix.substring(1)
        if (body.isEmpty()) return
        val toks = splitTop(body)
        check(scores.size == toks.size,
              "score count ${scores.size}, expected ${toks.size}")
        for (i in toks.indices) {
            val got = scores[i].toDouble()
            check(doubleMatches(got, toks[i]),
                  "row $i score %.9g does not match %s", got, toks[i])
        }
    }

    private fun keyPart(expected: String): String =
        expected.substringBefore('|')

    private fun suffixPart(expected: String): String =
            if (expected.contains('|')) expected.substring(expected.indexOf('|')) else ""

    private fun Scenario.textBody(tok: String): String {
        check(tok.length >= 3 && tok[0] == 't' && tok[1] == '(' && tok.last() == ')',
              "expected a t(...) literal, got %s", tok)
        return tok.substring(2, tok.length - 1)
    }

    private fun Scenario.listBody(tok: String): String {
        check(tok.length >= 3 && tok[0] == 'k' && tok[1] == '(' && tok.last() == ')',
              "expected a k(...) list, got %s", tok)
        return tok.substring(2, tok.length - 1)
    }

    // ------------------------------------------------------------------
    // Predicate / enum helpers
    // ------------------------------------------------------------------

    private fun Scenario.fieldCmp(path: String, op: String, v: Any?): Predicate {
        if (op !in setOf("eq", "ne", "lt", "le", "gt", "ge")) fail("bad cmp op %s", op)
        val f = field(path)
        return when (op) {
            "eq" -> f.eq(v)
            "ne" -> f.ne(v)
            "lt" -> f.lt(v)
            "le" -> f.le(v)
            "gt" -> f.gt(v)
            else -> f.ge(v)
        }
    }

    private fun Scenario.parseMetric(s: String): Metric = when (s) {
        "cosine" -> Metric.COSINE
        "dot" -> Metric.DOT
        "l2" -> Metric.L2
        else -> fail("bad metric %s", s)
    }

    private fun Scenario.parseQuant(s: String): Quant = when (s) {
        "none" -> Quant.NONE
        "binary" -> Quant.BINARY
        "scalar" -> Quant.SCALAR
        else -> fail("bad quant %s", s)
    }

    private fun Scenario.parseFieldType(s: String): FieldType = when (s) {
        "any" -> FieldType.ANY
        "bool" -> FieldType.BOOL
        "int" -> FieldType.INT
        "float" -> FieldType.FLOAT
        "text" -> FieldType.TEXT
        "bytes" -> FieldType.BYTES
        "vector" -> FieldType.VECTOR
        "array" -> FieldType.ARRAY
        "map" -> FieldType.MAP
        else -> fail("bad field type %s", s)
    }

    private fun Scenario.filteredCount(p: Predicate): Long =
        attempting { docs().query().filter(p).count() }
            .getOrElse { fail("filtered count failed: $it") }

    private fun Scenario.expectNum(expected: String, got: Long) {
        check(parseI64(expected) == got, "expected $got, want $expected")
    }

    // ------------------------------------------------------------------
    // OP implementations (the C harness's run_line, op for op)
    // ------------------------------------------------------------------

    private fun Scenario.runLine(op: String, args: String, expected: String) {
        val a = if (args.isNotEmpty()) splitTop(args) else emptyList()

        // ---- pure value ops (no db; raw handle layer) ----
        when (op) {
            "VERSION" -> {
                check(Natives.nFfiVersion() == 1,
                      "FFI_VERSION must be 1, got ${Natives.nFfiVersion()}")
                return
            }
            "VTYPE" -> {
                val names = listOf("null", "bool", "int", "float", "text",
                                   "bytes", "array", "map", "vector")
                val v = encode(a[0])
                val t = Natives.nValueType(v)
                check(t in 0..8, "type tag $t out of range")
                check(expected == names[t], "type ${names[t]}, want $expected")
                Natives.nValueFree(v)
                return
            }
            "VLEN" -> {
                val v = encode(a[0])
                expectNum(expected, Natives.nValueLen(v))
                Natives.nValueFree(v)
                return
            }
            "VAS_INT", "VAS_FLOAT", "VAS_BOOL" -> {
                val v = encode(a[0])
                val ok = IntArray(1)
                when (op) {
                    "VAS_INT" -> {
                        val got = Natives.nValueAsInt(v, ok)
                        if (expected == "fail") check(ok[0] == 0, "as_int unexpectedly ok ($got)")
                        else {
                            check(ok[0] == 1, "as_int failed")
                            check(expected == "ok:$got", "as_int ok:$got, want $expected")
                        }
                    }
                    "VAS_FLOAT" -> {
                        val got = Natives.nValueAsFloat(v, ok)
                        if (expected == "fail") check(ok[0] == 0, "as_float unexpectedly ok")
                        else {
                            check(ok[0] == 1, "as_float failed")
                            check(expected.startsWith("ok:"),
                                  "as_float expectation must be ok:<double>, got $expected")
                            check(doubleMatches(got, expected.substring(3)),
                                  "as_float 0x%016x (%g) does not match %s",
                                  got.toRawBits(), got, expected.substring(3))
                        }
                    }
                    else -> {
                        val got = Natives.nValueAsBool(v, ok)
                        if (expected == "fail") check(ok[0] == 0, "as_bool unexpectedly ok")
                        else {
                            check(ok[0] == 1, "as_bool failed")
                            val want = if (got) "ok:1" else "ok:0"
                            check(expected == want, "as_bool $want, want $expected")
                        }
                    }
                }
                Natives.nValueFree(v)
                return
            }
            "VTEXT_REF", "VBYTES_REF", "VVECTOR_REF" -> {
                val v = encode(a[0])
                when (op) {
                    "VTEXT_REF" -> {
                        val got = Natives.nValueTextRef(v)
                        check(got != null, "text_ref returned NULL for a text value")
                        check(String(got!!, Charsets.UTF_8) == textBody(expected),
                              "text bytes differ")
                    }
                    "VBYTES_REF" -> {
                        val got = Natives.nValueBytesRef(v)
                        check(got != null, "bytes_ref returned NULL for a bytes value")
                        check(expected.length >= 3 && expected.startsWith("b("),
                              "bytes expectation must be b(...), got $expected")
                        check(got!!.contentEquals(
                            expected.substring(2, expected.length - 1)
                                .toByteArray(Charsets.ISO_8859_1)), "bytes differ")
                    }
                    else -> {
                        val got = Natives.nValueVectorRef(v)
                        check(got != null, "vector_ref returned NULL for a vector value")
                        val want = lit(a[0]) as FloatArray
                        check(got!!.size == want.size,
                              "ref dim ${got.size}, rebuilt dim ${want.size}")
                        for (i in want.indices) {
                            check(got[i].toRawBits() == want[i].toRawBits(),
                                  "vector elem $i differs bit-exactly")
                        }
                    }
                }
                Natives.nValueFree(v)
                return
            }
            "VNEST", "VCLONE" -> {
                val root = encode(a[0])
                val holder = if (op == "VCLONE") Natives.nValueClone(root).also {
                    check(it != 0L, "clone failed")
                } else root
                val child = walkHandlePath(holder, a[1])
                if (expected == "absent") {
                    check(child == 0L, "path unexpectedly present")
                } else {
                    check(child != 0L, "path unexpectedly absent, want $expected")
                    checkValue(Values.decode(child), expected)
                }
                if (holder != root) Natives.nValueFree(holder)
                Natives.nValueFree(root)
                return
            }
            "VPUSH" -> {
                val arr = encode(a[0])
                val item = encode(a[1])
                check(Natives.nValueArrayPush(arr, item) == 0, "array_push failed") // consumes
                expectNum(expected, Natives.nValueLen(arr))
                Natives.nValueFree(arr)
                return
            }
            "VPUT" -> {
                val m = encode(a[0])
                val value = encode(a[2])
                check(Natives.nValueMapPut(m, Values.utf8(a[1]), value) == 0,
                      "map_put failed") // consumes
                expectNum(expected, Natives.nValueLen(m))
                Natives.nValueFree(m)
                return
            }
            "VMAP_KEYS" -> {
                // The v0.3.0 key iterator over a LITERAL: ascending
                // key-BYTE order whatever the construction order; empty
                // maps, non-maps, and scalars answer an EMPTY cursor.
                val v = encode(a[0])
                val keys = mutableListOf<String>()
                val cursor = Natives.nValueMapKeys(v)
                check(cursor != 0L, "value_map_keys failed")
                while (true) {
                    val kb = Natives.nStrsNext(cursor) ?: break
                    keys.add(String(kb, Charsets.UTF_8))
                }
                Natives.nStrsFree(cursor)
                Natives.nValueFree(v)
                checkKeys(keys, expected)
                return
            }
            "NULLFREES" -> {
                Natives.nNullFrees() // every _free(NULL) shape is a no-op (§7)
                return
            }
        }

        // ---- db-required ops from here on ----
        when (op) {
            "COLL" -> { setColl(a[0]); return }
            "INSERT", "INSERT_ERR" -> {
                val e = attempting { docs().insert(a[0].toByteArray(), lit(a[1])) }.exceptionOrNull()
                if (op == "INSERT_ERR") expectErr(e, errToken(expected)) else expectOk(e)
                return
            }
            "LEN" -> {
                expectNum(expected, attempting { docs().len() }
                    .getOrElse { fail("len failed: $it") })
                return
            }
            "GET", "GETFIELD" -> {
                if (op == "GETFIELD") {
                    // Raw walk (the C harness's walk_path): a NULL child
                    // pointer is "absent", a Null VALUE is a present
                    // child — the two stay distinguishable here.
                    val status = IntArray(1)
                    val vh = Natives.nGet(docs().h, a[0].toByteArray(), status)
                    check(status[0] == 0, "get failed")
                    check(vh != 0L, "GETFIELD on an absent document")
                    val child = walkHandlePath(vh, a[1])
                    if (expected == "absent") {
                        check(child == 0L, "field unexpectedly present")
                    } else {
                        check(child != 0L, "field unexpectedly absent, want $expected")
                        checkValue(Values.decode(child), expected)
                    }
                    Natives.nValueFree(vh)
                    return
                }
                val doc = attempting { docs().get(a[0].toByteArray()) }
                    .getOrElse { fail("get failed: $it") }
                if (expected == "absent") {
                    check(doc == null, "expected absence, got a document: ${render(doc)}")
                } else {
                    check(doc != null || expected == "null",
                          "expected a document, got absence")
                    checkValue(doc, expected)
                }
                return
            }
            "GET_KEYS" -> {
                // Key enumeration over a DECODED document (fetch by key
                // first, the decode-from-storage half bindings need):
                // the storage round-trip keeps every key; ascending
                // byte order.
                val status = IntArray(1)
                val vh = Natives.nGet(docs().h, a[0].toByteArray(), status)
                check(status[0] == 0, "get failed")
                check(vh != 0L, "GET_KEYS on an absent document")
                val keys = mutableListOf<String>()
                val cursor = Natives.nValueMapKeys(vh)
                check(cursor != 0L, "value_map_keys failed")
                while (true) {
                    val kb = Natives.nStrsNext(cursor) ?: break
                    keys.add(String(kb, Charsets.UTF_8))
                }
                Natives.nStrsFree(cursor)
                Natives.nValueFree(vh)
                checkKeys(keys, expected)
                return
            }
            "PUTMANY", "PUTMANY_ROLLBACK" -> {
                check(a.size % 2 == 0, "PUTMANY wants key/literal pairs")
                val count = a.size / 2
                val keys = (0 until count).map { a[2 * it].toByteArray() }
                val docsLit = (0 until count).map { lit(a[2 * it + 1]) }
                val e = attempting { docs().putMany(keys, docsLit) }.exceptionOrNull()
                if (op == "PUTMANY_ROLLBACK") expectErr(e, errToken(expected)) else expectOk(e)
                return
            }
            "INSERT_AUTO" -> {
                val key = attempting { docs().insertAuto(lit(a[0])) }
                    .getOrElse { fail("insertAuto failed: $it") }
                check(key.size == 20, "auto key length ${key.size}, want 20")
                var id = 0L
                for (b in key) {
                    check(b in '0'.code..'9'.code, "auto key not zero-padded digits")
                    id = id * 10 + (b - '0'.code)
                }
                check(lastAutoId == 0L || id > lastAutoId,
                      "auto id $id not monotonic (previous $lastAutoId)")
                lastAutoId = id
                return
            }
            "UPDATE" -> {
                attempting {
                    docs().update(a[0].toByteArray()) { current ->
                        var n = 0L
                        if (current != null) {
                            val m = current as? Map<*, *>
                                ?: error("update_bump: current doc is not a map")
                            val f = m["n"] ?: error("update_bump: current doc lacks field n")
                            n = f as? Long ?: error("update_bump: field n is not an int")
                        }
                        mapOf("n" to n + 1)
                    }
                }.onFailure { fail("update failed: $it") }
                return
            }
            "UPDATE_ABORT" -> {
                // Both halves of the callback ruling (PLAN rule 6): the
                // thrown marker surfaces at the call site, AND the
                // engine recorded the §1.6 abort (code 12 + message) in
                // the same-thread slot, read immediately after.
                val thrown = attempting {
                    docs().update(a[0].toByteArray()) { throw marker }
                }.exceptionOrNull()
                check(thrown === marker,
                      "the marker exception must surface at the call site, got $thrown")
                check(Natives.nLastErrorCode() == ErrCode.ARGUMENT.value,
                      "engine must record CORVID_E_ARGUMENT (12) for the aborted " +
                          "update, got ${Natives.nLastErrorCode()}")
                val msg = Natives.nLastErrorMessage()
                check(msg != null && msg.isNotEmpty(),
                      "abort code recorded but the message is missing")
                return
            }
            "PATCH" -> {
                attempting { docs().patch(a[0].toByteArray(), lit(a[1])) }
                    .onFailure { fail("patch failed: $it") }
                return
            }
            "CAS" -> {
                val ex = if (a[1] == "absent") null else lit(a[1])
                val re = if (a[2] == "absent") null else lit(a[2])
                val applied = attempting {
                    docs().compareAndSet(a[0].toByteArray(), ex, re)
                }.getOrElse { fail("compareAndSet failed: $it") }
                val want = if (applied) "applied:1" else "applied:0"
                check(expected == want, "CAS applied=$applied, want $expected")
                return
            }
            "DELETE" -> {
                val existed = attempting { docs().delete(a[0].toByteArray()) }
                    .getOrElse { fail("delete failed: $it") }
                val want = if (existed) "existed:1" else "existed:0"
                check(expected == want, "delete existed=$existed, want $expected")
                return
            }
            "DELETE_WHERE" -> {
                val removed = attempting {
                    docs().deleteWhere(fieldCmp(a[0], a[1], lit(a[2])))
                }.getOrElse { fail("deleteWhere failed: $it") }
                check(expected == "removed:$removed", "removed $removed, want $expected")
                return
            }
            "DELETE_IN" -> {
                val vals = a.drop(1).map { lit(it) }
                val removed = attempting {
                    docs().deleteWhere(field(a[0]).isIn(vals))
                }.getOrElse { fail("deleteWhere(in) failed: $it") }
                check(expected == "removed:$removed", "removed $removed, want $expected")
                return
            }
            "DELETE_BATCH" -> {
                val removed = attempting {
                    docs().deleteBatch(a.map { it.toByteArray() })
                }.getOrElse { fail("deleteBatch failed: $it") }
                check(expected == "removed:$removed", "removed $removed, want $expected")
                return
            }
            "INSERT_TTL" -> {
                attempting { docs().insertTTL(a[0].toByteArray(), lit(a[1]), parseI64(a[2])) }
                    .onFailure { fail("insertTTL failed: $it") }
                return
            }
            "GET_TTL" -> {
                val at = attempting { docs().getTTL(a[0].toByteArray()) }
                    .getOrElse { fail("getTTL failed: $it") }
                val got = if (at != null) "ttl:$at" else "nottl"
                check(expected == got, "ttl $got, want $expected")
                return
            }
            "SET_TTL" -> {
                attempting { docs().setTTL(a[0].toByteArray(), parseI64(a[1])) }
                    .onFailure { fail("setTTL failed: $it") }
                return
            }
            "PURGE" -> {
                val purged = attempting { docs().purgeExpired(parseI64(a[0])) }
                    .getOrElse { fail("purgeExpired failed: $it") }
                check(expected == "purged:$purged", "purged $purged, want $expected")
                return
            }
            "SCAN", "SCAN_STOP" -> {
                val stop = if (op == "SCAN_STOP") parseInt(a[0]) else 0
                var count = 0
                attempting {
                    docs().scan { _, _ ->
                        count++
                        stop <= 0 || count < stop
                    }
                }.onFailure { fail("scan failed: $it") }
                expectNum(expected, count.toLong())
                return
            }
            "PAGE" -> {
                val after = if (a[0] == "-") null else a[0].toByteArray()
                val page = attempting { docs().page(after, parseInt(a[1])) }
                    .getOrElse { fail("page failed: $it") }
                checkKeys(rowKeys(page.rows), keyPart(expected))
                val sp = suffixPart(expected)
                val want = if (page.nextAfter == null) "|end" else "|more"
                check(sp == want, "page cursor ${if (page.nextAfter == null) "end" else "more"}, want $sp")
                return
            }
        }

        // ---- predicates + queries ----
        when (op) {
            "QF_COUNT" -> { expectNum(expected, filteredCount(fieldCmp(a[0], a[1], lit(a[2])))); return }
            "QF_EXISTS" -> { expectNum(expected, filteredCount(field(a[0]).exists())); return }
            "QF_BETWEEN" -> {
                expectNum(expected, filteredCount(field(a[0]).between(lit(a[1]), lit(a[2]))))
                return
            }
            "QF_STARTS", "QF_CONTAINS" -> {
                val body = textBody(a[1])
                val p = if (op == "QF_STARTS") field(a[0]).startsWith(body)
                        else field(a[0]).contains(body)
                expectNum(expected, filteredCount(p))
                return
            }
            "QF_GEO" -> {
                expectNum(expected, filteredCount(
                    field(a[0]).geoWithin(parseDouble(a[1]), parseDouble(a[2]), parseDouble(a[3]))))
                return
            }
            "QF_AND", "QF_OR" -> {
                val l = fieldCmp(a[0], a[1], lit(a[2]))
                val r = fieldCmp(a[3], a[4], lit(a[5]))
                expectNum(expected, filteredCount(if (op == "QF_AND") l and r else l or r))
                return
            }
            "QF_NOT" -> {
                expectNum(expected, filteredCount(fieldCmp(a[0], a[1], lit(a[2])).not()))
                return
            }
            "PRED_FREE" -> {
                fieldCmp(a[0], a[1], lit(a[2])).close() // never-consumed-root free
                return
            }
            "Q_ABANDON" -> {
                docs().query().close() // abandoned-builder free path
                return
            }
            "QVEC", "APPROX" -> {
                val q = docs().query()
                if (op == "APPROX") q.approx()
                val rows = attempting {
                    q.vector(a[0], lit(a[1]) as FloatArray, parseInt(a[2]), Metric.COSINE).run()
                }.getOrElse { fail("query failed: $it") }
                checkKeys(rowKeys(rows), keyPart(expected))
                checkScores(rowScores(rows), suffixPart(expected))
                return
            }
            "QTEXT" -> {
                val rows = attempting {
                    docs().query().text(a[0], textBody(a[1]), parseInt(a[2])).run()
                }.getOrElse { fail("text query failed: $it") }
                checkKeys(rowKeys(rows), expected)
                return
            }
            "PHRASE", "PHRASE_K0" -> {
                // The v0.3.0 direct positional search through the
                // binding's phraseSearch: order-sensitive adjacency,
                // BM25 phrase scores in the score suffix; PHRASE_K0 is
                // the inert k==0 shape — an EMPTY result, never an error.
                val rows = attempting {
                    docs().phraseSearch(a[0], textBody(a[1]), parseInt(a[2]))
                }.getOrElse { fail("phraseSearch failed: $it") }
                checkKeys(rowKeys(rows), keyPart(expected))
                checkScores(rowScores(rows), suffixPart(expected))
                if (op == "PHRASE_K0") check(rows.isEmpty(), "k == 0 must answer an empty cursor")
                return
            }
            "HYBRID", "HYBRID_F" -> {
                // args: vfield vec k tfield t(query) tk [tagvalue] limit —
                // the tagvalue (HYBRID_F) slides the limit to the LAST slot.
                val tagged = op == "HYBRID_F"
                val vk = parseInt(a[2])
                val tk = parseInt(a[5])
                val limitIdx = if (tagged) 7 else 6
                val filter = if (tagged) field("tag").eq(lit(a[6]))
                             else field("kind").eq("doc")
                val rows = attempting {
                    docs().query()
                        .filter(filter)
                        .vector(a[0], lit(a[1]) as FloatArray, vk, Metric.COSINE)
                        .text(a[3], textBody(a[4]), tk)
                        .fuseRRF(60.0f)
                        .rerankMMR(1.0f)
                        .limit(parseInt(a[limitIdx]))
                        .run()
                }.getOrElse { fail("hybrid query failed: $it") }
                checkKeys(rowKeys(rows), keyPart(expected))
                checkScores(rowScores(rows), suffixPart(expected))
                return
            }
            "ORDER_BY" -> {
                val rows = attempting {
                    docs().query()
                        .orderBy(a[0], parseInt(a[1]) != 0)
                        .offset(parseInt(a[2]))
                        .limit(parseInt(a[3]))
                        .run()
                }.getOrElse { fail("order_by failed: $it") }
                checkKeys(rowKeys(rows), expected)
                return
            }
            "SELECT" -> {
                // args: (field,field,...) k(row-key); expected: that
                // row's projected document.
                check(a[0].length >= 2 && a[0][0] == '(' && a[0].last() == ')',
                      "SELECT's first arg must be a (field,...) group, got ${a[0]}")
                val fields = splitTop(a[0].substring(1, a[0].length - 1))
                val rows = attempting {
                    docs().query().select(*fields.toTypedArray()).run()
                }.getOrElse { fail("select query failed: $it") }
                val wantKey = listBody(a[1])
                var doc: Any? = null
                var found = false
                for (r in rows) {
                    if (String(r.key, Charsets.UTF_8) == wantKey) {
                        doc = r.doc
                        found = true
                    }
                }
                check(found, "row $wantKey not in the result")
                checkValue(doc, expected)
                return
            }
            "AGG_COUNT" -> {
                expectNum(expected, attempting { docs().query().count() }
                    .getOrElse { fail("count failed: $it") })
                return
            }
            "AGG_DISTINCT" -> {
                expectNum(expected, attempting { docs().query().countDistinct(a[0]) }
                    .getOrElse { fail("countDistinct failed: $it") })
                return
            }
            "AGG_SUM" -> {
                val sum = attempting { docs().query().sum(a[0]) }
                    .getOrElse { fail("sum failed: $it") }
                check(doubleMatches(sum, expected), "sum %.17g vs $expected", sum)
                return
            }
            "AGG_AVG" -> {
                val avg = attempting { docs().query().avg(a[0]) }
                    .getOrElse { fail("avg failed: $it") }
                if (expected == "none") check(avg == null, "avg $avg, want none")
                else {
                    check(avg != null, "avg null, want $expected")
                    check(doubleMatches(avg!!, expected), "avg %.17g vs $expected", avg)
                }
                return
            }
            "AGG_MIN", "AGG_MAX" -> {
                val out = attempting {
                    if (op == "AGG_MIN") docs().query().min(a[0]) else docs().query().max(a[0])
                }.getOrElse { fail("min/max failed: $it") }
                if (expected == "absent") check(out == null, "expected absence")
                else {
                    check(out != null, "expected a value, got absence")
                    checkValue(out, expected)
                }
                return
            }
            "AGG_GCOUNT", "AGG_GSUM", "AGG_GAVG" -> {
                val groups = attempting {
                    when (op) {
                        "AGG_GCOUNT" -> docs().query().groupCount(a[0])
                        "AGG_GSUM" -> docs().query().groupSum(a[0], a[1])
                        else -> docs().query().groupAvg(a[0], a[1])
                    }
                }.getOrElse { fail("group aggregate failed: $it") }
                // §7 inert rule exercised once with a NULL handle.
                check(Natives.nGroupIterNilNextOK(),
                      "NULL-handle groupiter_next must answer 0")
                check(expected.length >= 3 && expected[0] == 'g' && expected[1] == '(' &&
                      expected.last() == ')', "group expectation must be g(...), got $expected")
                val body = expected.substring(2, expected.length - 1)
                val pairs = if (body.isEmpty()) emptyList() else splitTop(body)
                check(groups.size == pairs.size,
                      "group count ${groups.size}, expected ${pairs.size}")
                for (i in pairs.indices) {
                    val eq = pairs[i].lastIndexOf('=')
                    check(eq > 0, "group pair needs key=val, got ${pairs[i]}")
                    val key = pairs[i].substring(0, eq)
                    val vtok = pairs[i].substring(eq + 1)
                    check(groups[i].key == key, "group key ${groups[i].key}, want $key")
                    check(doubleMatches(groups[i].value, vtok),
                          "group $key value %.17g vs $vtok", groups[i].value)
                }
                return
            }
        }

        // ---- graph ----
        when (op) {
            "LINK" -> {
                attempting { docs().link(a[0].toByteArray(), a[1], a[2].toByteArray()) }
                    .onFailure { fail("link failed: $it") }
                return
            }
            "LINK_W" -> {
                attempting {
                    docs().linkWeighted(a[0].toByteArray(), a[1], a[2].toByteArray(),
                                        parseDouble(a[3]))
                }.onFailure { fail("linkWeighted failed: $it") }
                return
            }
            "UNLINK" -> {
                val removed = attempting {
                    docs().unlink(a[0].toByteArray(), a[1], a[2].toByteArray())
                }.getOrElse { fail("unlink failed: $it") }
                val want = if (removed) "removed:1" else "removed:0"
                check(expected == want, "unlink removed=$removed, want $expected")
                return
            }
            "NEIGHBORS", "IN_NEIGHBORS" -> {
                val keys = attempting {
                    if (op == "NEIGHBORS") docs().neighbors(a[0].toByteArray(), a[1])
                    else docs().inNeighbors(a[0].toByteArray(), a[1])
                }.getOrElse { fail("neighbors failed: $it") }
                checkKeys(bytesKeys(keys), expected)
                return
            }
            "NEIGHBORS_W" -> {
                val weighted = attempting {
                    docs().neighborsWeighted(a[0].toByteArray(), a[1])
                }.getOrElse { fail("neighborsWeighted failed: $it") }
                check(expected.length >= 3 && expected[0] == 'g' && expected[1] == '(' &&
                      expected.last() == ')', "weighted expectation must be g(...), got $expected")
                val body = expected.substring(2, expected.length - 1)
                val pairs = if (body.isEmpty()) emptyList() else splitTop(body)
                check(weighted.size == pairs.size,
                      "weighted hits ${weighted.size}, expected ${pairs.size}")
                for (i in pairs.indices) {
                    val eq = pairs[i].lastIndexOf('=')
                    check(eq > 0, "weighted pair needs key=val, got ${pairs[i]}")
                    val key = pairs[i].substring(0, eq)
                    val vtok = pairs[i].substring(eq + 1)
                    check(String(weighted[i].key, Charsets.UTF_8) == key,
                          "weighted key ${weighted[i].key}, want $key")
                    check(doubleMatches(weighted[i].weight, vtok),
                          "weight of $key %.17g vs $vtok", weighted[i].weight)
                }
                return
            }
            "TRAVERSE" -> {
                val keys = attempting {
                    docs().traverse(a[0].toByteArray(), a[1], parseInt(a[2]))
                }.getOrElse { fail("traverse failed: $it") }
                checkKeys(bytesKeys(keys), expected)
                return
            }
        }

        // ---- geo ----
        when (op) {
            "GINSERT", "GINSERT_M" -> {
                val loc: Any? = if (op == "GINSERT_M") { // {lat, lon} map form
                    mapOf("lat" to parseDouble(a[1]), "lon" to parseDouble(a[2]))
                } else {
                    listOf(parseDouble(a[1]), parseDouble(a[2]))
                }
                attempting { docs().insert(a[0].toByteArray(), mapOf("loc" to loc)) }
                    .onFailure { fail("geo insert failed: $it") }
                return
            }
            "RADIUS", "NEAREST", "BBOX" -> {
                val hits = attempting {
                    when (op) {
                        "RADIUS" -> docs().geoWithinRadius(a[0], parseDouble(a[1]),
                                                           parseDouble(a[2]), parseDouble(a[3]))
                        "NEAREST" -> docs().geoNearest(a[0], parseDouble(a[1]),
                                                       parseDouble(a[2]), parseInt(a[3]))
                        else -> docs().geoWithinBBox(a[0], parseDouble(a[1]), parseDouble(a[2]),
                                                     parseDouble(a[3]), parseDouble(a[4]))
                    }
                }.getOrElse { fail("geo query failed: $it") }
                checkKeys(hits.map { String(it.key, Charsets.UTF_8) }, keyPart(expected))
                val sp = suffixPart(expected)
                if (sp.isNotEmpty()) {
                    check(sp[0] == '|', "geo suffix must start with |, got $sp")
                    val body = sp.substring(1)
                    val toks = if (body.isEmpty()) emptyList() else splitTop(body)
                    check(hits.size == toks.size,
                          "distance count ${hits.size}, expected ${toks.size}")
                    for (i in toks.indices) {
                        check(doubleMatches(hits[i].distanceKm, toks[i]),
                              "hit $i distance %.9g vs ${toks[i]}", hits[i].distanceKm)
                    }
                }
                return
            }
            "BBOX_ERR" -> {
                val e = attempting {
                    docs().geoWithinBBox(a[0], parseDouble(a[1]), parseDouble(a[2]),
                                         parseDouble(a[3]), parseDouble(a[4]))
                }.exceptionOrNull()
                expectErr(e, errToken(expected))
                return
            }
        }

        // ---- schema & indexes ----
        when (op) {
            "SET_SCHEMA" -> {
                val defs = splitTop(args).map { spec ->
                    val part = spec.split("#")
                    check(part.size == 4, "field spec needs name#type#required#unique, got $spec")
                    FieldDef(part[0], parseFieldType(part[1]), part[2] == "1", part[3] == "1")
                }
                attempting { docs().setSchema(*defs.toTypedArray()) }
                    .onFailure { fail("setSchema failed: $it") }
                return
            }
            "SCHEMA" -> {
                val tn = mapOf(
                    FieldType.ANY to "any", FieldType.BOOL to "bool", FieldType.INT to "int",
                    FieldType.FLOAT to "float", FieldType.TEXT to "text",
                    FieldType.BYTES to "bytes", FieldType.VECTOR to "vector",
                    FieldType.ARRAY to "array", FieldType.MAP to "map",
                )
                val defs = attempting { docs().schema() }
                    .getOrElse { fail("schema read failed: $it") }
                check(defs != null, "a schema must be declared first")
                val got = defs!!.joinToString(",") { f ->
                    "%s/%s/%d/%d".format(f.name, tn[f.type],
                                         if (f.required) 1 else 0, if (f.unique) 1 else 0)
                }
                check(expected == got, "schema $got, want $expected")
                return
            }
            "SCHEMA9" -> {
                val names = listOf("f_any", "f_bool", "f_int", "f_float", "f_text",
                                   "f_bytes", "f_vector", "f_array", "f_map")
                val types = listOf(FieldType.ANY, FieldType.BOOL, FieldType.INT,
                                   FieldType.FLOAT, FieldType.TEXT, FieldType.BYTES,
                                   FieldType.VECTOR, FieldType.ARRAY, FieldType.MAP)
                val defs = names.indices.map { i ->
                    FieldDef(names[i], types[i], i == 1, i == 8)
                }
                attempting { docs().setSchema(*defs.toTypedArray()) }
                    .onFailure { fail("setSchema(9) failed: $it") }
                val got = attempting { docs().schema() }
                    .getOrElse { fail("schema read failed: $it") }
                check(got != null, "the 9-field schema must be declared")
                val tags = got!!.mapIndexed { i, f ->
                    check(i < 9 && f.type == types[i] && f.name == names[i],
                          "field $i did not round-trip")
                    f.type.value.toString()
                }
                check(got.size == 9, "expected exactly 9 fields, saw ${got.size}")
                val joined = tags.joinToString(",")
                check(expected == joined, "schema9 $joined, want $expected")
                return
            }
            "SCHEMA_ERR" -> {
                val e = attempting { docs().insert(a[0].toByteArray(), lit(a[1])) }
                    .exceptionOrNull()
                expectErr(e, errToken(expected))
                return
            }
            "IDX_SCALAR" -> { attempting { docs().createScalarIndex(a[0]) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_COMPOUND" -> { attempting { docs().createCompoundIndex(*splitTop(args).toTypedArray()) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_TEXT" -> { attempting { docs().createTextIndex(a[0]) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_TEXT_DISK" -> { attempting { docs().createTextIndexOnDisk(a[0]) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_GEO" -> { attempting { docs().createGeoIndex(a[0]) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_VEC" -> { attempting { docs().createVectorIndex(a[0], parseMetric(a[1])) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_VEC_Q" -> { attempting { docs().createVectorIndexQuantized(a[0], parseMetric(a[1]), parseQuant(a[2])) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_VEC_DISK" -> { attempting { docs().createVectorIndexOnDisk(a[0], parseMetric(a[1])) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_VEC_DISK_Q" -> { attempting { docs().createVectorIndexOnDiskQuantized(a[0], parseMetric(a[1]), parseQuant(a[2])) }.onFailure { fail("idx failed: $it") }; return }
            "IDX_PQ", "IDX_PQ_DISK", "IDX_PQ_ERR" -> {
                val e = attempting {
                    if (op == "IDX_PQ_DISK") {
                        docs().createVectorIndexOnDiskPQ(a[0], parseMetric(a[1]),
                                                         parseInt(a[2]), parseInt(a[3]))
                    } else {
                        docs().createVectorIndexPQ(a[0], parseMetric(a[1]),
                                                   parseInt(a[2]), parseInt(a[3]))
                    }
                }.exceptionOrNull()
                if (op == "IDX_PQ_ERR") expectErr(e, errToken(expected)) else expectOk(e)
                return
            }
        }

        // ---- admin & persistence ----
        when (op) {
            "FILEDB" -> { openFile(dbPath); return }
            "FILEDB2" -> { openFile(db2Path); return }
            "DUMP" -> { attempting { db!!.dump(dumpPath.toString()) }.onFailure { fail("dump failed: $it") }; return }
            "LOAD" -> { attempting { db!!.load(dumpPath.toString()) }.onFailure { fail("load failed: $it") }; return }
            "LOAD_RENAMES" -> {
                val e = attempting {
                    db!!.loadWithRenames(dumpPath.toString(), mapOf(a[0] to a[1]))
                }.exceptionOrNull()
                if (expected.startsWith("err:")) expectErr(e, errToken(expected)) else expectOk(e)
                return
            }
            "COLLECTIONS" -> {
                val names = attempting { db!!.collections() }
                    .getOrElse { fail("collections failed: $it") }
                checkKeys(names, expected)
                return
            }
            "BACKUP" -> { attempting { db!!.backup(backupPath.toString()) }.onFailure { fail("backup failed: $it") }; return }
            "BACKUP_DUP" -> {
                val e = attempting { db!!.backup(backupPath.toString()) }.exceptionOrNull()
                expectErr(e, ErrCode.BACKUP_TARGET_EXISTS)
                return
            }
            "COMPACT_BUSY" -> {
                // Derived handles still open (docs()): the quiescence gate.
                val e = attempting { db!!.compact() }.exceptionOrNull()
                expectErr(e, ErrCode.BUSY)
                return
            }
            "COMPACT" -> {
                closeColl() // quiesce: the derived-handle gate (§4.13)
                attempting { db!!.compact() }.onFailure { fail("compact failed: $it") }
                docs() // re-acquire for subsequent lines
                return
            }
            "REOPEN" -> {
                val path = dbPath.toString()
                closeDb()
                db = attempting { open(path) }.getOrElse { fail("reopen of $path failed: $it") }
                docs()
                return
            }
        }

        fail("unknown OP %s", op)
    }

    // Walks a child path like "a.b.0.c" over a raw handle; 0 when
    // absent. The visited children are borrowed views; the harness
    // never frees them (FFI.md §5).
    private fun walkHandlePath(root: Long, path: String): Long {
        var cur = root
        var i = 0
        while (i < path.length && cur != 0L) {
            if (path[i] == '.') i++
            var j = i
            while (j < path.length && path[j] != '.') j++
            val seg = path.substring(i, j)
            if (seg.isEmpty()) break
            cur = if (seg.all { it in '0'..'9' }) {
                Natives.nValueArrayGet(cur, seg.toLong())
            } else {
                Natives.nValueMapGet(cur, Values.utf8(seg))
            }
            i = j
        }
        return cur
    }

    // ------------------------------------------------------------------
    // Fixture-file driver
    // ------------------------------------------------------------------

    // values.txt runs against no db; every other file starts in-memory
    // (admin/persist switch to file dbs via their OPs).
    private fun startsWithDb(path: String): Boolean =
        path.substringAfterLast('/') != "values.txt"

    private fun runFixture(name: String) {
        val path = Path.of("golden", "$name.txt")
        val text = String(Files.readAllBytes(path), Charsets.UTF_8)
        val stem = name
        val dir = Files.createTempDirectory("corvid-jvm-golden-$stem")
        val s = Scenario(
            file = path.toString(),
            workdir = dir,
            dbPath = dir.resolve("$stem.redb"),
            db2Path = dir.resolve("$stem-2.redb"),
            dumpPath = dir.resolve("$stem.dump"),
            backupPath = dir.resolve("$stem.backup.redb"),
        )
        if (startsWithDb(s.file)) s.openMemory()

        try {
            val lines = text.split('\n')

            // `lines` is counted in an INDEPENDENT pre-scan (the same
            // rule the Rust/C/Go drivers apply), so a dispatch loop
            // that skips a counted line — a stray continue, a swallowed
            // branch — diverges from `executed` below, instead of the
            // two fields silently reading one counter.
            var counted = 0
            for (raw in lines) {
                var first = 0
                while (first < raw.length && (raw[first] == ' ' || raw[first] == '\r')) first++
                if (first < raw.length && raw[first] != '#') counted++
            }

            var executed = 0
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                if (line.isEmpty() || line[0] == '#') continue
                s.line = executed + 1
                val parts = line.split('\t', limit = 3)
                val op = parts[0]
                val args = if (parts.size >= 2) parts[1] else ""
                val expected = if (parts.size >= 3) parts[2] else ""
                s.op = op
                try {
                    s.runLine(op, args, expected)
                } catch (e: AssertionError) {
                    throw AssertionError("${e.message} (at $name line ${s.line})", e)
                }
                executed++
            }

            check(executed == counted) {
                "FAIL ${s.file}: dispatched $executed of $counted counted executable lines"
            }
            println("SMOKE ${s.file} lines=$counted executed=$executed")
        } finally {
            s.closeDb()
            // scratch files die with the temp dir; best-effort cleanup
            s.workdir.toFile().deleteRecursively()
        }
    }

    // The suite: every fixture, one test each, 267 executable lines at
    // the pinned v0.3.0. Vendored byte-identical under golden/; fetch.sh
    // byte-compares them against the release's copies on every fetch.
    @Test
    fun values() = runFixture("values")

    @Test
    fun mutations() = runFixture("mutations")

    @Test
    fun queries() = runFixture("queries")

    @Test
    fun schema() = runFixture("schema")

    @Test
    fun geo() = runFixture("geo")

    @Test
    fun graph() = runFixture("graph")

    @Test
    fun admin() = runFixture("admin")

    @Test
    fun persist() = runFixture("persist")
}
