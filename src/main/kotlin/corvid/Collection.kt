// Collection.kt — the collection handle: mutations, reads, TTL, graph,
// geo, indexes, schema, phrase search, and the query entry (FFI.md
// §4.2/§4.8–§4.13). Threading: concurrent-safe like Db (ruling 8);
// close() idempotent.
package corvid

import corvid.jni.Natives
import corvid.jni.ScanSink
import corvid.jni.UpdateFn

class Collection internal constructor(internal val h: Long) : AutoCloseable {

    init {
        Corvid.load()
    }

    internal var closed = false
        private set

    /** The collection's name (round-trips the handle's stored name). */
    val name: String by lazy {
        ensureOpen()
        val b = Natives.nCollectionName(h)
        if (b == null) Values.throwLastError()
        String(b, Charsets.UTF_8)
    }

    // ---- mutations (§4.8) ----

    /** Insert or overwrite the document at `key` — atomic with all index
     *  maintenance and unique checks. */
    fun insert(key: ByteArray, doc: Any?) {
        ensureOpen()
        Values.check(Natives.nInsert(h, key, doc))
    }

    /** Single-transaction bulk load: one commit instead of N; the whole
     *  batch rolls back on a schema/unique violation; duplicate keys
     *  inside one batch are last-write-wins. */
    fun putMany(keys: List<ByteArray>, docs: List<Any?>) {
        ensureOpen()
        Values.check(Natives.nPutMany(h, keys.toTypedArray(),
            LongArray(docs.size) { i -> Values.encode(docs[i]) }))
    }

    /** Insert under a fresh, monotonically increasing zero-padded
     *  20-digit key; returns the key. */
    fun insertAuto(doc: Any?): ByteArray {
        ensureOpen()
        return Natives.nInsertAuto(h, doc) ?: Values.throwLastError()
    }

    /** Read-modify-write `key`. The closure receives the current document
     *  (null when absent — not an error) and returns the replacement;
     *  returning null DELETES the key; THROWING aborts (the store stays
     *  untouched, the engine records ARGUMENT, and the exception surfaces
     *  here at the call site — PLAN ruling 6).
     *
     *  Not linearizable against concurrent writers — use [compareAndSet]
     *  when that matters. The closure must not call back into this db. */
    fun update(key: ByteArray, fn: (Any?) -> Any?) {
        ensureOpen()
        Values.check(Natives.nUpdate(h, key, UpdateFn(fn)))
    }

    /** Merge `patch`'s top-level fields into the map at `key` (creating it
     *  if absent); a non-map on either side replaces the document. */
    fun patch(key: ByteArray, patch: Any?) {
        ensureOpen()
        Values.check(Natives.nPatch(h, key, patch))
    }

    /** Atomic conditional write. Nullability is semantic: `expected ==
     *  null` means "must be absent"; `replacement == null` means "delete
     *  if it matches". Returns whether the compare applied (a failed
     *  compare is NOT an error). */
    fun compareAndSet(key: ByteArray, expected: Any?, replacement: Any?): Boolean {
        ensureOpen()
        val applied = IntArray(1)
        Values.check(Natives.nCompareAndSet(h, key, expected, replacement, applied))
        return applied[0] != 0
    }

    /** Remove the document at `key`; returns whether one was removed.
     *  Cascades the key's graph edges in the same transaction. */
    fun delete(key: ByteArray): Boolean {
        ensureOpen()
        val existed = IntArray(1)
        Values.check(Natives.nDelete(h, key, existed))
        return existed[0] != 0
    }

    /** Delete every matching document — consumes `pred`. Returns the
     *  number removed (index-accelerated). */
    fun deleteWhere(pred: Predicate): Long {
        ensureOpen()
        val ph = pred.take()
        val removed = LongArray(1)
        Values.check(Natives.nDeleteWhere(h, ph, removed))
        return removed[0]
    }

    /** Delete each of `keys`; returns how many existed. Each delete
     *  cascades that key's graph edges. */
    fun deleteBatch(keys: List<ByteArray>): Long {
        ensureOpen()
        val removed = LongArray(1)
        Values.check(Natives.nDeleteBatch(h, keys.toTypedArray(), removed))
        return removed[0]
    }

    // ---- TTL (§4.8) ----

    /** Insert with expiry `expiresAt` (the caller's epoch; the engine
     *  keeps no clock) — the row and its expiry commit atomically. */
    fun insertTTL(key: ByteArray, doc: Any?, expiresAt: Long) {
        ensureOpen()
        Values.check(Natives.nInsertTTL(h, key, doc, expiresAt))
    }

    /** Set (or replace) `key`'s expiry without rewriting the document. A
     *  plain (non-TTL) write clears a previously set expiry. */
    fun setTTL(key: ByteArray, expiresAt: Long) {
        ensureOpen()
        Values.check(Natives.nSetTTL(h, key, expiresAt))
    }

    /** `key`'s expiry, or null when none is set. */
    fun getTTL(key: ByteArray): Long? {
        ensureOpen()
        val at = LongArray(1)
        val has = IntArray(1)
        Values.check(Natives.nGetTTL(h, key, at, has))
        return if (has[0] != 0) at[0] else null
    }

    /** Delete every record whose expiry is <= `now` (INCLUSIVE); returns
     *  the count. Each candidate is re-verified in the delete txn. */
    fun purgeExpired(now: Long): Long {
        ensureOpen()
        val purged = LongArray(1)
        Values.check(Natives.nPurgeExpired(h, now, purged))
        return purged[0]
    }

    // ---- reads (§4.9) ----

    /** Fetch and decode the document at `key`, or null when absent.
     *  (A stored Null value also decodes to null — the engine's
     *  absence/Null conflation, same as every sibling binding.) */
    fun get(key: ByteArray): Any? {
        ensureOpen()
        val status = IntArray(1)
        val vh = Natives.nGet(h, key, status)
        Values.check(status[0])
        if (vh == 0L) return null
        val v = Values.decode(vh)
        Natives.nValueFree(vh)
        return v
    }

    /** Fetch just these top-level fields of `key`'s document (absent
     *  fields are absent from the result; null when the key is absent). */
    fun getFields(key: ByteArray, vararg fields: String): Map<String, Any?>? {
        ensureOpen()
        val status = IntArray(1)
        val vh = Natives.nGet(h, key, status)
        Values.check(status[0])
        if (vh == 0L) return null
        try {
            val out = LinkedHashMap<String, Any?>(fields.size)
            for (f in fields) {
                val child = Natives.nValueMapGet(vh, Values.utf8(f))
                out[f] = if (child != 0L) Values.decode(child) else null
            }
            return out
        } finally {
            Natives.nValueFree(vh)
        }
    }

    /** Stream every (key, document) to `fn` in key order. Return false to
     *  stop (stopping is not an error). Constant memory. Throwing stops
     *  the scan and surfaces at the call site (ruling 6). The callback
     *  must not call back into this db (§1.6). */
    fun scan(fn: (key: ByteArray, doc: Any?) -> Boolean) {
        ensureOpen()
        Values.check(Natives.nScan(h, ScanSink(fn)))
    }

    /** Keyset pagination: up to `limit` documents in key order strictly
     *  after `after` (null starts at the beginning), from one snapshot. */
    fun page(after: ByteArray?, limit: Int): Page {
        ensureOpen()
        val res = Natives.nPage(h, after, limit.toLong()) ?: Values.throwLastError()
        val rowsHandle = (res[0] as Number).toLong()
        return Page(Query.walkRows(rowsHandle), res[1] as ByteArray?)
    }

    /** The document count — O(1) maintained counter. */
    fun len(): Long {
        ensureOpen()
        val out = LongArray(1)
        Values.check(Natives.nLen(h, out))
        return out[0]
    }

    // ---- queries (§4.6/§4.7) ----

    /** Begin a fluent query over this collection. */
    fun query(): Query {
        ensureOpen()
        val q = Natives.nQueryNew(h)
        if (q == 0L) Values.throwLastError()
        return Query(q)
    }

    /** DIRECT positional text search (the engine's phrase_search): the
     *  `k` most relevant documents whose `field` TEXT contains `phrase`
     *  as a consecutive, in-order run of analyzed tokens (stop words
     *  collapse out of adjacency on both sides). Rows carry documents;
     *  Row.score is the BM25 phrase sum — NOT the builder's fused RRF
     *  scale. `k == 0` yields an empty result (inert, not an error). */
    fun phraseSearch(field: String, phrase: String, k: Int): List<Row> {
        ensureOpen()
        val rows = Natives.nPhraseSearch(h, Values.utf8(field),
                                         Values.utf8(phrase), k.toLong())
        if (rows == 0L) Values.throwLastError()
        return Query.walkRows(rows)
    }

    // ---- graph (§4.11) ----

    /** Add a directed edge `from --relation--> to` (idempotent; a plain
     *  link overwrites a prior weighted edge's weight with 1.0). */
    fun link(from: ByteArray, relation: String, to: ByteArray) {
        ensureOpen()
        Values.check(Natives.nLink(h, from, Values.utf8(relation), to))
    }

    fun linkWeighted(from: ByteArray, relation: String, to: ByteArray,
                     weight: Double) {
        ensureOpen()
        Values.check(Natives.nLinkWeighted(h, from, Values.utf8(relation), to, weight))
    }

    /** Remove the edge (and its reverse) atomically; returns whether the
     *  FORWARD edge existed. */
    fun unlink(from: ByteArray, relation: String, to: ByteArray): Boolean {
        ensureOpen()
        val removed = IntArray(1)
        Values.check(Natives.nUnlink(h, from, Values.utf8(relation), to, removed))
        return removed[0] != 0
    }

    /** Targets of every `from --relation--> ?` edge, in key order. */
    fun neighbors(from: ByteArray, relation: String): List<ByteArray> =
        walkStrs(Natives.nNeighbors(h, from, Values.utf8(relation)))

    /** Sources of every `? --relation--> to` edge, in key order. */
    fun inNeighbors(to: ByteArray, relation: String): List<ByteArray> =
        walkStrs(Natives.nInNeighbors(h, to, Values.utf8(relation)))

    /** (target, weight) for every `from --relation--> ?` edge, in key
     *  order (1.0 for unweighted edges). */
    fun neighborsWeighted(from: ByteArray, relation: String): List<WeightedEdge> {
        ensureOpen()
        val hits = Natives.nNeighborsWeighted(h, from, Values.utf8(relation))
        if (hits == 0L) Values.throwLastError()
        val out = ArrayList<WeightedEdge>()
        try {
            while (true) {
                val hit = Natives.nGeohitsNext(hits) ?: break
                out.add(WeightedEdge(hit[0] as ByteArray, hit[1] as Double))
            }
        } finally {
            Natives.nGeohitsFree(hits)
        }
        return out
    }

    /** Breadth-first traversal following `relation` up to `hops` from
     *  `start`: the reachable nodes EXCLUDING start, each once, in BFS
     *  order. `hops == 0` yields nothing; cycles terminate. */
    fun traverse(start: ByteArray, relation: String, hops: Int): List<ByteArray> =
        walkStrs(Natives.nTraverse(h, start, Values.utf8(relation), hops.toLong()))

    // ---- geo (§4.12) ----

    /** Documents whose `field` point lies within `radiusKm` (INCLUSIVE)
     *  of (lat, lon), nearest first, ties by key. */
    fun geoWithinRadius(field: String, lat: Double, lon: Double,
                        radiusKm: Double): List<GeoHit> {
        ensureOpen()
        val hits = Natives.nGeoWithinRadius(h, Values.utf8(field), lat, lon, radiusKm)
        if (hits == 0L) Values.throwLastError()
        return walkGeohits(hits)
    }

    /** Documents whose `field` point lies inside the box, in KEY order.
     *  Bounds validated at entry ([ErrCode.ARGUMENT] on NaN / latitude
     *  out of [-90,90] / inverted latitude); `minLon > maxLon` wraps the
     *  antimeridian. distanceKm is the 0.0 sentinel. */
    fun geoWithinBBox(field: String, minLat: Double, minLon: Double,
                      maxLat: Double, maxLon: Double): List<GeoHit> {
        ensureOpen()
        val hits = Natives.nGeoWithinBBox(h, Values.utf8(field), minLat, minLon,
                                          maxLat, maxLon)
        if (hits == 0L) Values.throwLastError()
        return walkGeohits(hits)
    }

    /** The true `k` nearest documents by `field` point, nearest first. */
    fun geoNearest(field: String, lat: Double, lon: Double, k: Int): List<GeoHit> {
        ensureOpen()
        val hits = Natives.nGeoNearest(h, Values.utf8(field), lat, lon, k.toLong())
        if (hits == 0L) Values.throwLastError()
        return walkGeohits(hits)
    }

    // ---- indexes (§4.10) ----

    fun createScalarIndex(field: String) {
        ensureOpen()
        Values.check(Natives.nCreateScalarIndex(h, Values.utf8(field)))
    }

    fun createCompoundIndex(vararg fields: String) {
        ensureOpen()
        Values.check(Natives.nCreateCompoundIndex(h, fields.map { Values.utf8(it) }
            .toTypedArray()))
    }

    fun createTextIndex(field: String) {
        ensureOpen()
        Values.check(Natives.nCreateTextIndex(h, Values.utf8(field)))
    }

    fun createTextIndexOnDisk(field: String) {
        ensureOpen()
        Values.check(Natives.nCreateTextIndexOnDisk(h, Values.utf8(field)))
    }

    fun createGeoIndex(field: String) {
        ensureOpen()
        Values.check(Natives.nCreateGeoIndex(h, Values.utf8(field)))
    }

    fun createVectorIndex(field: String, metric: Metric) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndex(h, Values.utf8(field), metric.value))
    }

    fun createVectorIndexQuantized(field: String, metric: Metric, quant: Quant) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndexQuantized(h, Values.utf8(field),
                                                         metric.value, quant.value))
    }

    fun createVectorIndexOnDisk(field: String, metric: Metric) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndexOnDisk(h, Values.utf8(field),
                                                      metric.value))
    }

    fun createVectorIndexOnDiskQuantized(field: String, metric: Metric, quant: Quant) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndexOnDiskQuantized(h, Values.utf8(field),
                                                               metric.value, quant.value))
    }

    /** Product-quantized in-memory HNSW: a codebook of `m` subspaces ×
     *  `k` centroids trains from existing vectors; `dim % m == 0`
     *  required. Every training-domain failure surfaces as
     *  [ErrCode.EMPTY_INDEX_TRAINING]. */
    fun createVectorIndexPQ(field: String, metric: Metric, m: Int, k: Int) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndexPQ(h, Values.utf8(field),
                                                  metric.value, m.toLong(), k.toLong()))
    }

    fun createVectorIndexOnDiskPQ(field: String, metric: Metric, m: Int, k: Int) {
        ensureOpen()
        Values.check(Natives.nCreateVectorIndexOnDiskPQ(h, Values.utf8(field),
                                                        metric.value, m.toLong(), k.toLong()))
    }

    // ---- schema (§4.10) ----

    /** Declare (or replace) the collection's schema: enforced on
     *  subsequent writes only — existing documents are not retroactively
     *  validated. */
    fun setSchema(vararg defs: FieldDef) {
        ensureOpen()
        Values.check(Natives.nSetSchema(
            h,
            defs.map { Values.utf8(it.name) }.toTypedArray(),
            IntArray(defs.size) { i -> defs[i].type.value },
            BooleanArray(defs.size) { i -> defs[i].required },
            BooleanArray(defs.size) { i -> defs[i].unique },
        ))
    }

    /** The declared schema, or null when none is. */
    fun schema(): List<FieldDef>? {
        ensureOpen()
        val it = Natives.nSchema(h)
        if (it == 0L) return null
        val out = ArrayList<FieldDef>()
        try {
            while (true) {
                val f = Natives.nSchemaIterNext(it) ?: break
                out.add(FieldDef(
                    f[0] as String,
                    FieldType.entries.first { it.value == (f[1] as Number).toInt() },
                    f[2] as Boolean,
                    f[3] as Boolean,
                ))
            }
        } finally {
            Natives.nSchemaIterFree(it)
        }
        return out
    }

    /** Release this handle's engine reference and derived count
     *  (quiescence for Db.compact). Idempotent (AutoCloseable, so
     *  `use` works). */
    override fun close() {
        if (closed) return
        closed = true
        Natives.nCollectionFree(h)
    }

    private fun ensureOpen() {
        check(!closed) { "corvid: Collection is closed" }
    }

    private fun walkStrs(cursor: Long): List<ByteArray> {
        ensureOpen()
        if (cursor == 0L) Values.throwLastError()
        val out = ArrayList<ByteArray>()
        try {
            while (true) {
                out.add(Natives.nStrsNext(cursor) ?: break)
            }
        } finally {
            Natives.nStrsFree(cursor)
        }
        return out
    }

    private fun walkGeohits(hits: Long): List<GeoHit> {
        val out = ArrayList<GeoHit>()
        try {
            while (true) {
                val hit = Natives.nGeohitsNext(hits) ?: break
                out.add(GeoHit(hit[0] as ByteArray, hit[1] as Double, hit[2]))
            }
        } finally {
            Natives.nGeohitsFree(hits)
        }
        return out
    }
}
