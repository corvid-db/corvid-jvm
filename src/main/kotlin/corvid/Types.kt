// Types.kt — the enums and result shapes of the public API, mirroring
// the frozen FFI.md §1.4 enums and §4.6/§4.7/§4.12 outputs.
package corvid

/** The distance metric (corvid_metric). */
enum class Metric(val value: Int) {
    /** Cosine distance `1 - cos_sim` in `[0,2]`; zero-norm = maximally distant. */
    COSINE(0),

    /** Negated dot product (larger dot sorts first). */
    DOT(1),

    /** Squared Euclidean (monotonic with L2). */
    L2(2),
}

/** The stored-vector quantization mode (corvid_quant). */
enum class Quant(val value: Int) {
    /** Full f32 precision (`dim * 4` bytes/vector). */
    NONE(0),

    /** One bit per dimension (sign), Hamming; ~32x smaller. */
    BINARY(1),

    /** 8-bit per-vector min+scale; ~4x smaller. */
    SCALAR(2),
}

/** The declared type of a schema field (corvid_field_type). */
enum class FieldType(val value: Int) {
    ANY(0), BOOL(1), INT(2), FLOAT(3), TEXT(4), BYTES(5), VECTOR(6), ARRAY(7), MAP(8),
}

/** One declared schema field. */
class FieldDef(
    val name: String,
    val type: FieldType,
    val required: Boolean = false,
    val unique: Boolean = false,
)

/** A query result row: the document key, the (projected) document, and
 *  the ranking score — the fused RRF score for builder queries and
 *  page rows (0.0 for pure filter/order queries), the BM25 phrase sum
 *  for [Collection.phraseSearch] rows (its own scale). */
class Row(
    val key: ByteArray,
    val doc: Any?,
    val score: Float,
)

/** A `(group key, aggregate)` pair, in ascending group-key order. */
class Group(
    val key: String,
    val value: Double,
)

/** A geo hit: the document key, kilometres from the query point (the 0.0
 *  sentinel for bbox queries), and the document. */
class GeoHit(
    val key: ByteArray,
    val distanceKm: Double,
    val doc: Any?,
)

/** A weighted graph edge target: `weight` is 1.0 for unweighted links. */
class WeightedEdge(
    val key: ByteArray,
    val weight: Double,
)

/** One keyset page: the rows and the resume cursor (null at the end). */
class Page(
    val rows: List<Row>,
    val nextAfter: ByteArray?,
)
