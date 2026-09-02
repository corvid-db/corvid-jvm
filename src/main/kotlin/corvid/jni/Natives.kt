// Natives.kt — the raw JNI boundary of corvid-jvm.
//
// INTERNAL: the only place handles exist on the Kotlin side. Every
// `external fun` is implemented by native/corvid_jni.c (loaded from the
// artifacts fetched per docs/PLAN.md). Nothing here appears in the
// public API — corvid.Db/Collection/Query wrap it; the golden harness
// uses it directly for the value-family exercises that are inherently
// raw (the same split corvid-go made between its API and cgo.go).
//
// Conventions (mirroring corvid.h):
//   - handles are Long (0 = NULL / absent / failure — see per-fn docs);
//   - engine strings cross as ByteArray (REAL UTF-8 — JNI's modified
//     UTF-8 jstrings never touch the engine side, PLAN ruling 5);
//   - status-returning fns return Int (0 = CORVID_OK, 1 = CORVID_ERR)
//     with out-params passed as preallocated [1] arrays;
//   - cursor `next` fns return null at exhaustion;
//   - the §1.6 callbacks are fun interfaces so the C bridge can call
//     their single `invoke` through a cached method id.
package corvid.jni

/** corvid_scan's row sink (FFI.md §1.6): return false to STOP the scan. */
internal fun interface ScanSink {
    fun invoke(key: ByteArray, doc: Any?): Boolean
}

/** corvid_update's read-modify-write closure (FFI.md §1.6): null deletes
 *  the key; throwing aborts (and the exception surfaces at the call site). */
internal fun interface UpdateFn {
    fun invoke(current: Any?): Any?
}

internal object Natives {
    // NOTE: deliberately NO init-time load. Corvid.load() System.load()s
    // the shim and verifies the ABI version FIRST; every public entry
    // point funnels through it (an init-time Corvid.load() here would
    // recurse: load -> version check -> this object -> load ...).

    // ---- version + errors (§4.1) ----
    external fun nFfiVersion(): Int
    external fun nLastErrorCode(): Int
    external fun nLastErrorMessage(): ByteArray?

    // ---- value construction (§4.3) ----
    external fun nValueNull(): Long
    external fun nValueBool(v: Boolean): Long
    external fun nValueInt(v: Long): Long
    external fun nValueFloat(v: Double): Long
    external fun nValueText(bytes: ByteArray): Long
    external fun nValueBytes(bytes: ByteArray): Long
    external fun nValueVector(elems: FloatArray): Long
    external fun nValueArrayNew(): Long
    external fun nValueArrayPush(arr: Long, item: Long): Int // consumes item
    external fun nValueMapNew(): Long
    external fun nValueMapPut(map: Long, key: ByteArray, value: Long): Int // consumes value
    external fun nValueFree(v: Long)

    // ---- value reads (§4.4) ----
    external fun nValueType(v: Long): Int
    external fun nValueLen(v: Long): Long
    external fun nValueAsBool(v: Long, ok: IntArray): Boolean
    external fun nValueAsInt(v: Long, ok: IntArray): Long
    external fun nValueAsFloat(v: Long, ok: IntArray): Double
    external fun nValueTextRef(v: Long): ByteArray?
    external fun nValueBytesRef(v: Long): ByteArray?
    external fun nValueVectorRef(v: Long): FloatArray?
    external fun nValueArrayGet(v: Long, index: Long): Long // borrowed child (0 = absent)
    external fun nValueMapGet(v: Long, key: ByteArray): Long // borrowed child (0 = absent)
    external fun nValueMapKeys(v: Long): Long // strs cursor (0 on failure)
    external fun nValueClone(v: Long): Long

    // ---- strs cursor (§4.12) ----
    external fun nStrsNext(s: Long): ByteArray?
    external fun nStrsFree(s: Long)

    // ---- db (§4.1) ----
    external fun nOpen(path: ByteArray): Long
    external fun nOpenMemory(): Long
    external fun nClose(db: Long): Int
    external fun nCollections(db: Long): Long

    // ---- collection handle (§4.2) ----
    external fun nCollection(db: Long, name: ByteArray): Long
    external fun nCollectionName(c: Long): ByteArray?
    external fun nCollectionFree(c: Long)

    // ---- mutations (§4.8) ----
    external fun nInsert(c: Long, key: ByteArray, doc: Any?): Int
    external fun nPutMany(c: Long, keys: Array<ByteArray>, vals: LongArray): Int
    external fun nInsertAuto(c: Long, doc: Any?): ByteArray?
    external fun nUpdate(c: Long, key: ByteArray, fn: UpdateFn): Int
    external fun nPatch(c: Long, key: ByteArray, patch: Any?): Int
    external fun nCompareAndSet(c: Long, key: ByteArray, expected: Any?,
                                replacement: Any?, applied: IntArray): Int
    external fun nDelete(c: Long, key: ByteArray, existed: IntArray): Int
    external fun nDeleteWhere(c: Long, pred: Long, removed: LongArray): Int
    external fun nDeleteBatch(c: Long, keys: Array<ByteArray>, removed: LongArray): Int
    external fun nInsertTTL(c: Long, key: ByteArray, doc: Any?, expiresAt: Long): Int
    external fun nSetTTL(c: Long, key: ByteArray, expiresAt: Long): Int
    external fun nGetTTL(c: Long, key: ByteArray, expiresAtOut: LongArray,
                         hasTtl: IntArray): Int
    external fun nPurgeExpired(c: Long, now: Long, purged: LongArray): Int

    // ---- reads (§4.9) ----
    external fun nGet(c: Long, key: ByteArray, status: IntArray): Long
    external fun nScan(c: Long, sink: ScanSink): Int
    external fun nPage(c: Long, after: ByteArray?, limit: Long): Array<Any?>?
    external fun nLen(c: Long, out: LongArray): Int

    // ---- predicates (§4.5) ----
    external fun nPredExists(path: ByteArray): Long
    external fun nPredCompare(path: ByteArray, op: Int, value: Any?): Long
    external fun nPredIn(path: ByteArray, values: Array<Any?>): Long
    external fun nPredBetween(path: ByteArray, low: Any?, high: Any?): Long
    external fun nPredStartsWith(path: ByteArray, prefix: ByteArray): Long
    external fun nPredContains(path: ByteArray, substr: ByteArray): Long
    external fun nPredGeoWithin(path: ByteArray, lat: Double, lon: Double,
                                radiusKm: Double): Long
    external fun nPredAnd(a: Long, b: Long): Long
    external fun nPredOr(a: Long, b: Long): Long
    external fun nPredNot(a: Long): Long
    external fun nPredFree(p: Long)

    // ---- query builder + rows + aggregations (§4.6/§4.7) ----
    external fun nQueryNew(coll: Long): Long
    external fun nQueryFilter(q: Long, pred: Long): Int
    external fun nQueryVector(q: Long, field: ByteArray, query: FloatArray,
                              k: Long, metric: Int): Int
    external fun nQueryText(q: Long, field: ByteArray, s: ByteArray, k: Long): Int
    external fun nQueryFuseRRF(q: Long, k: Float): Int
    external fun nQueryRerankMMR(q: Long, lambda: Float): Int
    external fun nQueryApprox(q: Long): Int
    external fun nQueryLimit(q: Long, n: Long): Int
    external fun nQueryOffset(q: Long, n: Long): Int
    external fun nQueryOrderBy(q: Long, field: ByteArray, descending: Boolean): Int
    external fun nQuerySelect(q: Long, fields: Array<ByteArray>): Int
    external fun nQueryRun(q: Long): Long
    external fun nQueryFree(q: Long)
    external fun nRowsNext(rows: Long): Array<Any?>? // [ByteArray key, doc, Float score]
    external fun nRowsFree(rows: Long)
    external fun nPhraseSearch(c: Long, field: ByteArray, phrase: ByteArray,
                               k: Long): Long
    external fun nQueryCount(q: Long, out: LongArray): Int
    external fun nQueryCountDistinct(q: Long, field: ByteArray, out: LongArray): Int
    external fun nQuerySum(q: Long, field: ByteArray, out: DoubleArray): Int
    external fun nQueryAvg(q: Long, field: ByteArray, out: DoubleArray,
                           hasValue: IntArray): Int
    external fun nQueryMin(q: Long, field: ByteArray, status: IntArray): Long
    external fun nQueryMax(q: Long, field: ByteArray, status: IntArray): Long
    external fun nQueryGroupCount(q: Long, field: ByteArray): Long
    external fun nQueryGroupSum(q: Long, groupField: ByteArray,
                                valueField: ByteArray): Long
    external fun nQueryGroupAvg(q: Long, groupField: ByteArray,
                                valueField: ByteArray): Long
    external fun nGroupIterNext(it: Long): Array<Any?>? // [String key, Double value]
    external fun nGroupIterFree(it: Long)
    external fun nGroupIterNilNextOK(): Boolean

    // ---- graph (§4.11) ----
    external fun nLink(c: Long, from: ByteArray, relation: ByteArray, to: ByteArray): Int
    external fun nLinkWeighted(c: Long, from: ByteArray, relation: ByteArray,
                               to: ByteArray, weight: Double): Int
    external fun nUnlink(c: Long, from: ByteArray, relation: ByteArray,
                         to: ByteArray, removed: IntArray): Int
    external fun nNeighbors(c: Long, from: ByteArray, relation: ByteArray): Long
    external fun nInNeighbors(c: Long, to: ByteArray, relation: ByteArray): Long
    external fun nNeighborsWeighted(c: Long, from: ByteArray,
                                    relation: ByteArray): Long
    external fun nTraverse(c: Long, start: ByteArray, relation: ByteArray,
                           hops: Long): Long

    // ---- geo (§4.12) ----
    external fun nGeoWithinRadius(c: Long, field: ByteArray, lat: Double,
                                  lon: Double, radiusKm: Double): Long
    external fun nGeoWithinBBox(c: Long, field: ByteArray, minLat: Double,
                                minLon: Double, maxLat: Double,
                                maxLon: Double): Long
    external fun nGeoNearest(c: Long, field: ByteArray, lat: Double, lon: Double,
                             k: Long): Long
    external fun nGeohitsNext(h: Long): Array<Any?>? // [ByteArray, Double, doc?]
    external fun nGeohitsFree(h: Long)

    // ---- indexes (§4.10) ----
    external fun nCreateScalarIndex(c: Long, field: ByteArray): Int
    external fun nCreateCompoundIndex(c: Long, fields: Array<ByteArray>): Int
    external fun nCreateTextIndex(c: Long, field: ByteArray): Int
    external fun nCreateTextIndexOnDisk(c: Long, field: ByteArray): Int
    external fun nCreateGeoIndex(c: Long, field: ByteArray): Int
    external fun nCreateVectorIndex(c: Long, field: ByteArray, metric: Int): Int
    external fun nCreateVectorIndexQuantized(c: Long, field: ByteArray, metric: Int,
                                             quant: Int): Int
    external fun nCreateVectorIndexOnDisk(c: Long, field: ByteArray, metric: Int): Int
    external fun nCreateVectorIndexOnDiskQuantized(c: Long, field: ByteArray,
                                                   metric: Int, quant: Int): Int
    external fun nCreateVectorIndexPQ(c: Long, field: ByteArray, metric: Int,
                                      m: Long, k: Long): Int
    external fun nCreateVectorIndexOnDiskPQ(c: Long, field: ByteArray, metric: Int,
                                            m: Long, k: Long): Int

    // ---- schema (§4.10) ----
    external fun nSetSchema(c: Long, names: Array<ByteArray>, types: IntArray,
                            required: BooleanArray, unique: BooleanArray): Int
    external fun nSchema(c: Long): Long // 0 = none declared
    external fun nSchemaIterNext(it: Long): Array<Any?>? // [String, Int, Boolean, Boolean]
    external fun nSchemaIterFree(it: Long)

    // ---- admin & persistence (§4.13) ----
    external fun nDump(db: Long, path: ByteArray): Int
    external fun nLoad(db: Long, path: ByteArray): Int
    external fun nLoadWithRenames(db: Long, path: ByteArray, olds: Array<ByteArray>,
                                  news: Array<ByteArray>): Int
    external fun nBackup(db: Long, path: ByteArray): Int
    external fun nCompact(db: Long, movedOut: IntArray): Int

    // ---- the §7 no-op free exercises (golden: NULLFREES) ----
    external fun nNullFrees()
}
