// Predicate.kt — the filter DSL (FFI.md §4.5): field(path) builders and
// and/or/not combinators over engine-side predicate trees.
//
// Consumption (§8): and/or/not, Query.filter, and deleteWhere CONSUME
// their children unconditionally — success or failure alike. The Kotlin
// side marks itself consumed BEFORE the native call and never frees
// twice; using or closing a consumed Predicate throws.
package corvid

import corvid.jni.Natives

class Predicate internal constructor(internal val h: Long) {
    internal var consumed = false
        private set

    internal fun take(): Long {
        check(!consumed) { "corvid: Predicate already consumed or closed" }
        consumed = true
        return h
    }

    /** Logical conjunction — consumes both operands. */
    infix fun and(other: Predicate): Predicate {
        val a = take()
        val b = other.take()
        val r = Natives.nPredAnd(a, b)
        if (r == 0L) Values.throwLastError()
        return Predicate(r)
    }

    /** Logical disjunction — consumes both operands. */
    infix fun or(other: Predicate): Predicate {
        val a = take()
        val b = other.take()
        val r = Natives.nPredOr(a, b)
        if (r == 0L) Values.throwLastError()
        return Predicate(r)
    }

    /** Logical negation — consumes this predicate. */
    fun not(): Predicate {
        val a = take()
        val r = Natives.nPredNot(a)
        if (r == 0L) Values.throwLastError()
        return Predicate(r)
    }

    /** Free a never-consumed root. Idempotent; NOT for a predicate
     *  already consumed by and/or/not/filter/deleteWhere. */
    fun close() {
        if (consumed) return
        consumed = true
        Natives.nPredFree(h)
    }
}

/** The field DSL entry: `field("user.age").gt(30)`. Paths are
 *  dot-separated child paths. */
class FieldExpr internal constructor(private val path: String) {

    private fun bytes() = Values.utf8(path)

    private fun build(h: Long): Predicate {
        if (h == 0L) Values.throwLastError()
        return Predicate(h)
    }

    fun eq(v: Any?) = build(Natives.nPredCompare(bytes(), 0, v))
    fun ne(v: Any?) = build(Natives.nPredCompare(bytes(), 1, v))
    fun lt(v: Any?) = build(Natives.nPredCompare(bytes(), 2, v))
    fun le(v: Any?) = build(Natives.nPredCompare(bytes(), 3, v))
    fun gt(v: Any?) = build(Natives.nPredCompare(bytes(), 4, v))
    fun ge(v: Any?) = build(Natives.nPredCompare(bytes(), 5, v))

    fun exists() = build(Natives.nPredExists(bytes()))

    /** Inclusive [low, high] range. */
    fun between(low: Any?, high: Any?) =
        build(Natives.nPredBetween(bytes(), low, high))

    /** True when the value equals any element of [values] (empty
     *  matches nothing). */
    fun isIn(values: List<Any?>) =
        build(Natives.nPredIn(bytes(), values.toTypedArray()))

    fun isIn(vararg values: Any?) = isIn(values.toList())

    fun startsWith(prefix: String) =
        build(Natives.nPredStartsWith(bytes(), Values.utf8(prefix)))

    fun contains(s: String) =
        build(Natives.nPredContains(bytes(), Values.utf8(s)))

    /** Within `radiusKm` (INCLUSIVE, haversine) of (lat, lon); false on
     *  non-point values and missing paths. A negative radius matches
     *  nothing (the engine's rule — not an error). */
    fun geoWithin(lat: Double, lon: Double, radiusKm: Double) =
        build(Natives.nPredGeoWithin(bytes(), lat, lon, radiusKm))
}

/** Start a predicate over a (dot-separated) field path. */
fun field(path: String): FieldExpr = FieldExpr(path)
