// hybrid — the flagship: filter + vector + BM25, RRF fusion, MMR
// rerank, limit.
//
// Hybrid retrieval over a 4-document corpus: a pre-ranking `kind`
// filter, a vector (ANN) source and a BM25 text source, both
// contributing top-2 candidate lists, fused with Reciprocal Rank
// Fusion (k = 60) and reranked for diversity with MMR (lambda = 1.0),
// capped at 2 rows. The printed scores are RRF rank sums: s1 is rank 1
// of both sources (1/61 + 1/61 = 2/61), s3 rank 2 of both (2/62).
//
// Run: ./gradlew exampleHybrid   (after ./fetch.sh + scripts/build-native.sh)
package corvid.examples

import corvid.Metric
import corvid.field
import corvid.openMemory

// docs:begin:hybrid
fun main() {
    openMemory().use { db ->
        val docs = db.collection("docs")

        docs.insert("s1".toByteArray(), mapOf(
            "kind" to "doc", "body" to "rust embedded database",
            "v" to floatArrayOf(1.0f, 0.0f),
        ))
        docs.insert("s2".toByteArray(), mapOf(
            "kind" to "doc", "body" to "python web frameworks",
            "v" to floatArrayOf(0.0f, 1.0f),
        ))
        docs.insert("s3".toByteArray(), mapOf(
            "kind" to "doc", "body" to "rust again database",
            "v" to floatArrayOf(0.9f, 0.1f),
        ))
        docs.insert("m1".toByteArray(), mapOf("kind" to "meta")) // filtered out below

        // The flagship query: filter + vector + text, RRF + MMR + limit.
        val rows = docs.query()
            .filter(field("kind").eq("doc"))
            .vector("v", floatArrayOf(1.0f, 0.0f), 2, Metric.COSINE)
            .text("body", "rust database", 2)
            .fuseRRF(60.0f)
            .rerankMMR(1.0f)
            .limit(2)
            .select("body")
            .run()
        rows.forEachIndexed { rank, r ->
            println("%d. %s score=%.6f %s".format(rank + 1, String(r.key), r.score, r.doc))
        }

        docs.close()
    }
}
// docs:end:hybrid
