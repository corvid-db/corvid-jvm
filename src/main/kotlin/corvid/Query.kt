// Query.kt — the fluent query builder (FFI.md §4.6/§4.7): sources,
// fusion, paging, projection, the run terminal, and the aggregation
// terminals. Every terminal CONSUMES the builder (§8); close() frees
// an abandoned one. Single-thread object (ruling 8).
package corvid

import corvid.jni.Natives

class Query internal constructor(internal val h: Long) : AutoCloseable {
    internal var done = false
        private set

    private fun take(): Long {
        check(!done) { "corvid: Query already run, aggregated, or closed" }
        return h
    }

    private fun markDone() {
        done = true
    }

    /** Add a filter — consumes the predicate. Multiple calls AND together. */
    fun filter(p: Predicate): Query {
        val ph = p.take()
        Values.check(Natives.nQueryFilter(take(), ph))
        return this
    }

    /** Add a vector-search source: the `k` nearest documents by `field`
     *  embedding under `metric`. */
    fun vector(field: String, query: FloatArray, k: Int,
               metric: Metric = Metric.COSINE): Query {
        Values.check(Natives.nQueryVector(take(), Values.utf8(field), query,
                                          k.toLong(), metric.value))
        return this
    }

    /** Add a BM25 text-search source over `field`. */
    fun text(field: String, query: String, k: Int): Query {
        Values.check(Natives.nQueryText(take(), Values.utf8(field),
                                        Values.utf8(query), k.toLong()))
        return this
    }

    /** Set the Reciprocal Rank Fusion constant (engine default 60).
     *  Validated at execution: a non-finite or non-positive k fails run
     *  with [ErrCode.ARGUMENT]. */
    fun fuseRRF(k: Float): Query {
        Values.check(Natives.nQueryFuseRRF(take(), k))
        return this
    }

    /** Diversify results with Maximal Marginal Relevance. `lambda`
     *  outside [0,1] (NaN included) fails run with [ErrCode.ARGUMENT].
     *  Anchors on the first vector source; a no-op without one. */
    fun rerankMMR(lambda: Float): Query {
        Values.check(Natives.nQueryRerankMMR(take(), lambda))
        return this
    }

    /** Allow approximate execution: a filtered single-vector-source
     *  query may use its ANN index with over-fetch-then-filter. */
    fun approx(): Query {
        Values.check(Natives.nQueryApprox(take()))
        return this
    }

    fun limit(n: Int): Query {
        Values.check(Natives.nQueryLimit(take(), n.toLong()))
        return this
    }

    fun offset(n: Int): Query {
        Values.check(Natives.nQueryOffset(take(), n.toLong()))
        return this
    }

    /** Order by a scalar field instead of by rank (the engine's
     *  ordering contract: comparables first, incomparables after, rows
     *  missing the field last, ties by key; `descending` reverses
     *  within-class order only). */
    fun orderBy(field: String, descending: Boolean = false): Query {
        Values.check(Natives.nQueryOrderBy(take(), Values.utf8(field), descending))
        return this
    }

    /** Project result documents to these top-level fields. */
    fun select(vararg fields: String): Query {
        Values.check(Natives.nQuerySelect(take(), fields.map { Values.utf8(it) }
            .toTypedArray()))
        return this
    }

    /** Execute — consumes the builder. Returns the rows (empty for an
     *  empty result; failure throws, never an empty list). */
    fun run(): List<Row> {
        val rows = Natives.nQueryRun(take())
        markDone()
        if (rows == 0L) Values.throwLastError()
        return walkRows(rows)
    }

    /** Count matching documents (O(1) when unfiltered) — consumes. */
    fun count(): Long {
        val out = LongArray(1)
        val st = Natives.nQueryCount(take(), out)
        markDone()
        Values.check(st)
        return out[0]
    }

    /** Distinct values at `field` — consumes. */
    fun countDistinct(field: String): Long {
        val out = LongArray(1)
        val st = Natives.nQueryCountDistinct(take(), Values.utf8(field), out)
        markDone()
        Values.check(st)
        return out[0]
    }

    /** Sum the numeric values at `field` (all-skipped sums to 0.0) — consumes. */
    fun sum(field: String): Double {
        val out = DoubleArray(1)
        val st = Natives.nQuerySum(take(), Values.utf8(field), out)
        markDone()
        Values.check(st)
        return out[0]
    }

    /** Mean of the numeric values at `field`, or null when none exists — consumes. */
    fun avg(field: String): Double? {
        val out = DoubleArray(1)
        val has = IntArray(1)
        val st = Natives.nQueryAvg(take(), Values.utf8(field), out, has)
        markDone()
        Values.check(st)
        return if (has[0] != 0) out[0] else null
    }

    /** The minimum comparable value at `field`, or null when the filtered
     *  set holds none — consumes. */
    fun min(field: String): Any? {
        val status = IntArray(1)
        val h = Natives.nQueryMin(take(), Values.utf8(field), status)
        markDone()
        Values.check(status[0])
        if (h == 0L) return null
        val v = Values.decode(h)
        Natives.nValueFree(h)
        return v
    }

    fun max(field: String): Any? {
        val status = IntArray(1)
        val h = Natives.nQueryMax(take(), Values.utf8(field), status)
        markDone()
        Values.check(status[0])
        if (h == 0L) return null
        val v = Values.decode(h)
        Natives.nValueFree(h)
        return v
    }

    /** Count grouped by the value at `field`, ascending group-key order — consumes. */
    fun groupCount(field: String): List<Group> {
        val it = Natives.nQueryGroupCount(take(), Values.utf8(field))
        markDone()
        if (it == 0L) Values.throwLastError()
        return walkGroups(it)
    }

    fun groupSum(groupField: String, valueField: String): List<Group> {
        val it = Natives.nQueryGroupSum(take(), Values.utf8(groupField),
                                        Values.utf8(valueField))
        markDone()
        if (it == 0L) Values.throwLastError()
        return walkGroups(it)
    }

    fun groupAvg(groupField: String, valueField: String): List<Group> {
        val it = Natives.nQueryGroupAvg(take(), Values.utf8(groupField),
                                        Values.utf8(valueField))
        markDone()
        if (it == 0L) Values.throwLastError()
        return walkGroups(it)
    }

    /** Free a builder abandoned without executing. Idempotent
     *  (AutoCloseable, so `use` works). */
    override fun close() {
        if (done) return
        markDone()
        Natives.nQueryFree(h)
    }

    internal companion object {
        internal fun walkRows(rows: Long): List<Row> {
            val out = ArrayList<Row>()
            try {
                while (true) {
                    val r = Natives.nRowsNext(rows) ?: break
                    out.add(Row(r[0] as ByteArray, r[1], r[2] as Float))
                }
            } finally {
                Natives.nRowsFree(rows)
            }
            return out
        }

        internal fun walkGroups(it: Long): List<Group> {
            val out = ArrayList<Group>()
            try {
                while (true) {
                    val g = Natives.nGroupIterNext(it) ?: break
                    out.add(Group(g[0] as String, g[1] as Double))
                }
            } finally {
                Natives.nGroupIterFree(it)
            }
            return out
        }
    }
}
