// text-search — BM25 ranking, English and CJK.
//
// Six notes (three English, three CJK) searched through a text index
// with the query builder's BM25 source. Row scores are RRF ranks
// (1/(60 + rank)); the *order* is the BM25 ranking.
//
// The CJK strings exercise the engine's dictionary-free CJK
// segmentation: maximal runs of CJK characters are tokenized as
// sliding BIGRAMS (「東京」… → "東京", …), so an unsegmented CJK query
// matches by its bigrams — "城市" (city) matches both city notes,
// "数据库" (database) matches the ML note.
//
// Phrase matching: engine v0.3.0 added the DIRECT positional
// corvid_phrase_search to the ABI (consecutive in-order analyzed
// tokens, stop words collapsing out of adjacency), surfaced here as
// Collection.phraseSearch — Row.score is the BM25 phrase sum, not the
// builder's fused RRF scale.
//
// Run: ./gradlew exampleTextSearch
package corvid.examples

import corvid.openMemory

private val corpus = listOf(
    "n1" to "the quick brown fox jumps over the lazy dog",
    "n2" to "a quick red fox leaps over a sleeping dog",
    "n3" to "slow green turtle crosses the road",
    "n4" to "東京是一座巨大的城市",  // Tokyo is a huge city
    "n5" to "大阪是関西最大的城市",  // Osaka is Kansai's biggest city
    "n6" to "机器学习正在改变数据库", // ML is changing databases
)

private fun search(notes: corvid.Collection, query: String, label: String) {
    val rows = notes.query().text("body", query, 3).run()
    val line = rows.joinToString(" ") { r -> "%s(%.6f)".format(String(r.key), r.score) }
    println("%-28s -> %s".format(label, line))
}

private fun phrase(notes: corvid.Collection, query: String, label: String) {
    val rows = notes.phraseSearch("body", query, 3)
    val line = rows.joinToString(" ") { r -> "%s(%.6f)".format(String(r.key), r.score) }
    println("%-28s -> %s".format(label, line))
}

fun main() {
    openMemory().use { db ->
        val notes = db.collection("notes")

        for ((key, body) in corpus) {
            notes.insert(key.toByteArray(), mapOf("body" to body))
        }
        notes.createTextIndex("body")

        search(notes, "quick fox", "bm25 \"quick fox\":")
        search(notes, "quick dog", "bm25 \"quick dog\":")
        search(notes, "城市", "bm25 CJK 城市 (city):")
        search(notes, "数据库", "bm25 CJK 数据库 (database):")

        phrase(notes, "fox jumps over", "phrase \"fox jumps over\":")
        phrase(notes, "over jumps fox", "phrase \"over jumps fox\" (reversed — no match):")
        phrase(notes, "leaps over a sleeping", "phrase with stop words collapsed:")

        notes.close()
    }
}
