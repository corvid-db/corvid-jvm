// quickstart — the README tour as a runnable file.
//
// Open an in-memory database, create a collection, insert three small
// documents carrying 2-d embeddings, run a kNN vector query under
// cosine, and print the ranked rows. Close what you opened.
//
// Run: ./gradlew exampleQuickstart   (after ./fetch.sh + scripts/build-native.sh)
package corvid.examples

import corvid.Metric
import corvid.openMemory

// docs:begin:quickstart
fun main() {
    openMemory().use { db ->
        val docs = db.collection("docs")

        docs.insert("p1".toByteArray(), mapOf(
            "title" to "rust embedded database", "kind" to "doc",
            "v" to floatArrayOf(1.0f, 0.0f),
        ))
        docs.insert("p2".toByteArray(), mapOf(
            "title" to "python web frameworks", "kind" to "doc",
            "v" to floatArrayOf(0.0f, 1.0f),
        ))
        docs.insert("p3".toByteArray(), mapOf(
            "title" to "rust again database", "kind" to "doc",
            "v" to floatArrayOf(0.9f, 0.1f),
        ))

        // kNN: the 3 nearest documents to (1, 0) under cosine. Project
        // the field the printout needs (docs decode in full either way;
        // select trims the payload).
        val rows = docs.query()
            .vector("v", floatArrayOf(1.0f, 0.0f), 3, Metric.COSINE)
            .select("title")
            .run()
        rows.forEachIndexed { rank, r ->
            println("%d. %s score=%.6f %s".format(rank + 1, String(r.key), r.score, r.doc))
        }

        docs.close()
    }
}
// docs:end:quickstart
