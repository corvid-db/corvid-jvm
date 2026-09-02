// vector-index — three vector-index families, ANN vs exact.
//
// A file-backed database (the on-disk index is a disk-resident HNSW
// graph persisted inside the db file) with eight 4-d documents. The
// same embedding is stored under three fields so each index family can
// be demonstrated side by side:
//
//   vMem  — in-memory HNSW              (createVectorIndex)
//   vDisk — on-disk HNSW                (createVectorIndexOnDisk)
//   vQ    — in-memory binary-quantized  (createVectorIndexQuantized)
//
// The exact (streaming-scan) ranking is printed first, then the ANN
// (approx) ranking served by each index. The unquantized indexes
// answer identically to the scan on this corpus; the binary-quantized
// one genuinely diverges — the recall/footprint trade-off quantization
// makes (binary packs each float32 to one sign bit, ~32x smaller).
// Finally the db is closed and reopened: the on-disk graph reloads and
// serves the same ANN answer without a rebuild.
//
// Scores are RRF ranks (1/(60 + rank)) — the lone vector source's row
// score — so they reflect each lane's own ranking.
//
// Run: ./gradlew exampleVectorIndex
package corvid.examples

import corvid.Metric
import corvid.Quant
import corvid.open
import java.nio.file.Files
import java.nio.file.Path

private val corpus = listOf(
    "k0" to floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), // nearest
    "k1" to floatArrayOf(0.95f, 0.05f, 0.0f, 0.0f),
    "k2" to floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f),
    "k3" to floatArrayOf(0.0f, 0.9f, 0.1f, 0.0f),
    "k4" to floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f),
    "k5" to floatArrayOf(0.7f, 0.7f, 0.0f, 0.0f),
    "k6" to floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f),
    "k7" to floatArrayOf(0.98f, 0.02f, 0.0f, 0.0f),
)

private val probe = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun runQuery(items: corvid.Collection, field: String, approx: Boolean, label: String) {
    val q = items.query().vector(field, probe, 4, Metric.COSINE)
    if (approx) q.approx()
    val rows = q.run()
    val line = rows.joinToString(" ") { r -> "%s(%.6f)".format(String(r.key), r.score) }
    println("%-38s %s".format(label, line))
}

fun main() {
    val path = Path.of(System.getProperty("java.io.tmpdir"), "corvid-jvm-example-vector-index.redb")
    Files.deleteIfExists(path) // reruns start clean (single-file db)

    open(path.toString()).use { db ->
        val items = db.collection("items")
        for ((key, v) in corpus) {
            items.insert(key.toByteArray(), mapOf(
                "v_mem" to v, "v_disk" to v, "v_q" to v,
            ))
        }
        items.createVectorIndex("v_mem", Metric.COSINE)
        items.createVectorIndexOnDisk("v_disk", Metric.COSINE)
        items.createVectorIndexQuantized("v_q", Metric.COSINE, Quant.BINARY)

        println("top-4 nearest to (1,0,0,0) under cosine:")
        runQuery(items, "v_mem", false, "exact (scan):")
        runQuery(items, "v_mem", true, "ann in-memory HNSW:")
        runQuery(items, "v_disk", true, "ann on-disk HNSW:")
        runQuery(items, "v_q", true, "ann binary-quantized:")
        println("(the quantized lane trades recall for a ~32x smaller index)")

        items.close()
    }

    // Reopen: the on-disk graph reloads (no rebuild) and answers again.
    open(path.toString()).use { db ->
        val items = db.collection("items")
        runQuery(items, "v_disk", true, "ann on-disk after reopen:")
        items.close()
    }

    Files.deleteIfExists(path)
}
